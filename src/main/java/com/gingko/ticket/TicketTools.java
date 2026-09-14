package com.gingko.ticket;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.stream.Collectors;

/**
 * 工单查询工具（M2，E02）：Agent 的第一个业务工具。
 * 经 Toolkit.registerTool 反射注册（@Tool 方法进 basic 组，始终激活）。
 * M5（E05）：query_ticket 输出扩展建单字段（分类/优先级/摘要/对话上下文，FR-M5-04）。
 */
public class TicketTools {

    private final TicketStore store;
    private final String currentUser;

    public TicketTools(TicketStore store, String currentUser) {
        this.store = store;
        this.currentUser = currentUser;
    }

    @Tool(name = "query_ticket",
            description = "按工单号查询工单详情，返回状态、标题、分类、优先级、摘要、处理人、创建时间",
            readOnly = true,
            concurrencySafe = true)
    public String queryTicket(
            @ToolParam(name = "ticketId", description = "工单号，如 1024") String ticketId) {
        return store.findById(ticketId)
                .map(t -> "工单号：" + t.id()
                        + "\n标题：" + t.title()
                        + "\n状态：" + t.status()
                        + "\n分类：" + t.category() + "｜优先级：" + t.priority()
                        + "\n处理人：" + (t.assignee() == null ? "待分派" : t.assignee())
                        + "\n创建时间：" + t.createdAt()
                        + "\n摘要：" + t.summary()
                        + (t.contextSummary() == null || t.contextSummary().isBlank()
                                ? "" : "\n对话上下文：" + t.contextSummary()))
                .orElse("未找到工单 " + ticketId + "，请确认工单号是否正确");
    }

    @Tool(name = "list_my_tickets",
            description = "查询当前用户名下所有工单的摘要列表（工单号、标题、状态、处理人）",
            readOnly = true,
            concurrencySafe = true)
    public String listMyTickets() {
        var tickets = store.findByReporter(currentUser);
        if (tickets.isEmpty()) {
            return "你当前没有工单";
        }
        return "你共有 " + tickets.size() + " 个工单：\n"
                + tickets.stream()
                        .map(t -> "[" + t.id() + "] " + t.title() + " — " + t.status()
                                + "（处理人：" + (t.assignee() == null ? "待分派" : t.assignee()) + "）")
                        .collect(Collectors.joining("\n"));
    }
}
