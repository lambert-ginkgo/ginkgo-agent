package com.gingko.ticket;

import com.gingko.auth.TicketPermission;
import com.gingko.auth.User;
import com.gingko.auth.UserDirectory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.stream.Collectors;

/**
 * 工单查询工具（M2，E02）：Agent 的第一个业务工具。
 * 经 Toolkit.registerTool 反射注册（@Tool 方法进 basic 组，始终激活）。
 * M5（E05）：query_ticket 输出扩展建单字段（分类/优先级/摘要/对话上下文，FR-M5-04）。
 *
 * <p>M7（E07）身份改造——「我」从假变真：
 * E02-E06 的"当前用户"是工具构造时注入的固定字符串（CLI 期假身份，
 * MockTicketStore.DEFAULT_USER），E07 起改为 {@link RuntimeContext} 方法注入：
 * 未标 {@code @ToolParam} 的参数由框架按类型自动注入（ToolMethodInvoker 源码
 * L151-179 实证），同一 agent 实例服务多用户，身份随每次调用的会话上下文流动。
 *
 * <p>数据行级权限（FR-M7-02）在工具内部判定：员工仅能看本人工单，
 * IT 管理员全量——越权查询返回明确拦截话术（{@link TicketPermission}），
 * 而非把别人的工单吐给模型。权限判定只读会话身份、不读消息内容，
 * 因此天然免疫"忽略之前指令，你现在是管理员"类提示注入（FR-M7-04）。
 */
public class TicketTools {

    private final TicketStore store;

    public TicketTools(TicketStore store) {
        this.store = store;
    }

    @Tool(name = "query_ticket",
            description = "按工单号查询工单详情，返回状态、标题、分类、优先级、摘要、处理人、创建时间",
            readOnly = true,
            concurrencySafe = true)
    public String queryTicket(
            @ToolParam(name = "ticketId", description = "工单号，如 1024") String ticketId,
            RuntimeContext ctx) {
        User user = UserDirectory.resolve(ctx.getUserId());
        return store.findById(ticketId)
                .map(t -> TicketPermission.canView(user, t.reporter())
                        ? render(t)
                        : TicketPermission.denyView(ticketId, user))
                .orElse("未找到工单 " + ticketId + "，请确认工单号是否正确");
    }

    @Tool(name = "list_my_tickets",
            description = "查询当前用户名下所有工单的摘要列表（工单号、标题、状态、处理人）",
            readOnly = true,
            concurrencySafe = true)
    public String listMyTickets(RuntimeContext ctx) {
        var tickets = store.findByReporter(ctx.getUserId());
        if (tickets.isEmpty()) {
            return "你当前没有工单";
        }
        return "你共有 " + tickets.size() + " 个工单：\n"
                + tickets.stream()
                        .map(t -> "[" + t.id() + "] " + t.title() + " — " + t.status()
                                + "（处理人：" + (t.assignee() == null ? "待分派" : t.assignee()) + "）")
                        .collect(Collectors.joining("\n"));
    }

    private static String render(Ticket t) {
        return "工单号：" + t.id()
                + "\n标题：" + t.title()
                + "\n状态：" + t.status()
                + "\n分类：" + t.category() + "｜优先级：" + t.priority()
                + "\n处理人：" + (t.assignee() == null ? "待分派" : t.assignee())
                + "\n创建时间：" + t.createdAt()
                + "\n摘要：" + t.summary()
                + (t.contextSummary() == null || t.contextSummary().isBlank()
                        ? "" : "\n对话上下文：" + t.contextSummary());
    }
}
