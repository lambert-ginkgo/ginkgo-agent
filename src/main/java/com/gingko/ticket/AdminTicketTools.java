package com.gingko.ticket;

import com.gingko.auth.TicketPermission;
import com.gingko.auth.User;
import com.gingko.auth.UserDirectory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单管理工具（M7，E07）：IT 管理员专属能力——全量列表与工单状态处理。
 *
 * <p>放行策略的设计权衡（E07 判断素材）：单 agent 多用户共享 Toolkit，
 * 工具在 build 时注册、无法按会话动态换工具集，因此管理工具对员工
 * <b>在场但拦截</b>——工具注册给模型（员工对话时模型知道它存在、可以尝试调用），
 * 调用后按当次会话身份判定，员工身份返回明确拦截话术（FR-M7-03）。
 *
 * <p>与 E06 反静默建单的「工具不在场」是两种互补哲学：
 * <ul>
 *   <li><b>工具不在场</b>（create_ticket 不注册）：危险动作任何身份都不可达，
 *       确定性最高——适合"流程上就不该由模型做"的动作（落库确认门）；</li>
 *   <li><b>在场但按身份拦截</b>（本类）：能力对不同角色差异化开放，
 *       拦截有明确话术（模型能转述"需要找 IT 工程师"而不是装死）——
 *       适合"有人能做、有人不能做"的权限分野。这正是 T02 拆解里
 *       PermissionEngine tool-specific checks 的同款思路。</li>
 * </ul>
 *
 * <p>员工对话中模型调用本类工具不会造成越权：身份来自 RuntimeContext
 * （会话身份），不来自消息内容——提示注入改不了它。
 */
public class AdminTicketTools {

    /** 可通过 update_ticket_status 设置的状态（处理中/已解决/关闭/待人工）。 */
    private static final Set<String> MANAGEABLE_STATUSES = Set.of(
            Ticket.STATUS_IN_PROGRESS, Ticket.STATUS_RESOLVED, Ticket.STATUS_CLOSED, Ticket.STATUS_HUMAN);

    private final TicketStore store;

    public AdminTicketTools(TicketStore store) {
        this.store = store;
    }

    @Tool(name = "list_all_tickets",
            description = "查询系统内全部工单的摘要列表（含所有用户的工单，工单号、标题、状态、报告人、处理人）——"
                    + "仅 IT 管理员可用；普通员工只能查看本人工单",
            readOnly = true,
            concurrencySafe = true)
    public String listAllTickets(RuntimeContext ctx) {
        User user = UserDirectory.resolve(ctx.getUserId());
        if (!TicketPermission.canManage(user)) {
            return TicketPermission.denyManage("查看全部工单", user);
        }
        List<Ticket> tickets = store.findAll();
        if (tickets.isEmpty()) {
            return "系统当前没有工单";
        }
        return "系统共有 " + tickets.size() + " 个工单：\n"
                + tickets.stream()
                        .map(t -> "[" + t.id() + "] " + t.title() + " — " + t.status()
                                + "（报告人：" + t.reporter() + "，处理人："
                                + (t.assignee() == null ? "待分派" : t.assignee()) + "）")
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "update_ticket_status",
            description = "更新工单处理状态（处理中/已解决/关闭/待人工）——仅 IT 管理员可用，"
                    + "普通员工请求处理工单时应告知其等待 IT 工程师处理",
            concurrencySafe = false)
    public String updateTicketStatus(
            @ToolParam(name = "ticketId", description = "工单号，如 1024") String ticketId,
            @ToolParam(name = "status", description = "新状态：处理中/已解决/关闭/待人工") String status,
            RuntimeContext ctx) {
        User user = UserDirectory.resolve(ctx.getUserId());
        if (!TicketPermission.canManage(user)) {
            return TicketPermission.denyManage("修改工单状态", user);
        }
        String normalized = status == null ? "" : status.trim();
        if (!MANAGEABLE_STATUSES.contains(normalized)) {
            return "[更新失败] 状态必须是 处理中/已解决/关闭/待人工 之一（收到：" + normalized + "）";
        }
        return store.updateStatus(ticketId, normalized)
                .map(t -> "工单已更新：[" + t.id() + "] " + t.title() + " 状态 → " + t.status()
                        + "（处理人：" + (t.assignee() == null ? "待分派" : t.assignee()) + "）")
                .orElse("未找到工单 " + ticketId + "，请确认工单号是否正确");
    }
}
