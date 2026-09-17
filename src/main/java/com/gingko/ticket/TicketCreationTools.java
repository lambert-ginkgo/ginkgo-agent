package com.gingko.ticket;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 两阶段建单工具（M5，FR-M5-02/03）——E05 自由模式（ReAct 自主路由）的完整三件套：
 *
 * <ol>
 *   <li>{@code draft_ticket}：Agent 从对话中抽取字段，生成工单草稿卡（不落库），
 *       暂存草稿箱并返回卡片文本展示给员工——员工确认前可反复调用（重新抽取即修改）；</li>
 *   <li>{@code create_ticket}：员工明确确认后调用，将草稿箱里的<b>同一份草稿</b>落库，返回工单号。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>确认与落库的是同一份数据（草稿箱按 reporter 隔离），“员工看到的”与“落库的”不会漂移；</li>
 *   <li>工具层参数校验是分类/抽取错误的硬兜底：优先级词表外直接拒绝（提示模型先向员工追问，
 *       对应 M5 验收标准“模糊描述时追问而非瞎猜优先级”）；分类词表外归一化到“其他”（宽容）；</li>
 *   <li>create_ticket 无业务参数：落库内容只来自草稿箱，模型无法在确认环节偷换字段。</li>
 * </ul>
 *
 * <p>E06 变更：草稿箱外提为共享 {@link TicketDraftBox}（本类注入使用，行为不变）；
 * 图模式装配改用 {@link TicketDraftTools}（只有 draft_ticket，create/cancel 不注册——
 * 落库权收归图的 confirm 节点）。本类保留给自由模式（M5 验收回归）。
 *
 * <p>M7（E07）：当前用户从构造时固定字符串改为 {@link RuntimeContext} 方法注入，
 * 草稿箱按当次会话身份分桶（与图模式同口径）。
 */
public class TicketCreationTools {

    private final TicketStore store;

    /** 草稿箱：reporter → 待确认草稿（E06 外提为共享组件，与图 confirm 节点同源）。 */
    private final TicketDraftBox draftBox;

    public TicketCreationTools(TicketStore store) {
        this(store, new TicketDraftBox());
    }

    /** 注入共享草稿箱的构造（草稿状态跨组件可见）。 */
    public TicketCreationTools(TicketStore store, TicketDraftBox draftBox) {
        this.store = store;
        this.draftBox = draftBox;
    }

    @Tool(name = "draft_ticket",
            description = "根据对话内容抽取字段，生成工单草稿卡（不落库，等待员工确认）。"
                    + "员工要求修改字段时重新调用本工具更新草稿。"
                    + "category 取值：账号/网络/软件/硬件/权限/其他；priority 取值：高/中/低，"
                    + "必须依据对话中的影响范围或紧急程度，没有依据时先向员工追问，不要自行猜测",
            concurrencySafe = false)
    public String draftTicket(
            @ToolParam(name = "category", description = "工单分类：账号/网络/软件/硬件/权限/其他") String category,
            @ToolParam(name = "priority", description = "优先级：高/中/低（依据影响范围与紧急程度）") String priority,
            @ToolParam(name = "title", description = "工单标题，一句话概括问题或诉求") String title,
            @ToolParam(name = "summary", description = "问题摘要：现象、影响、员工诉求") String summary,
            @ToolParam(name = "contextSummary",
                    description = "对话上下文摘要：本次对话已排查的步骤与结论，供 IT 工程师接单参考；"
                            + "排查类对话转建单时必填，无排查过程传空字符串") String contextSummary,
            RuntimeContext ctx) {
        if (title == null || title.isBlank()) {
            return "[草稿生成失败] 工单标题不能为空——请先向员工了解问题或诉求，再生成草稿";
        }
        if (priority == null || !TicketDraft.PRIORITIES.contains(priority.trim())) {
            return "[草稿生成失败] 优先级必须是 高/中/低，且要有对话依据（影响范围、紧急程度）；"
                    + "信息不足时请先用一句话向员工确认（如：只影响你一个人还是整个部门？），不要猜";
        }
        TicketDraft draft = new TicketDraft(
                normalizeCategory(category),
                priority.trim(),
                title.trim(),
                orEmpty(summary),
                orEmpty(contextSummary));
        draftBox.put(ctx.getUserId(), draft);
        return "草稿已生成（未落库，等待员工确认）：\n\n" + draft.renderCard();
    }

    @Tool(name = "create_ticket",
            description = "确认创建工单：将当前工单草稿正式落库并返回工单号。"
                    + "仅在员工明确确认草稿内容（如回复“确认”“没问题”）后才调用；"
                    + "员工要求跳过确认直接建单时，仍必须先展示草稿卡等确认",
            concurrencySafe = false)
    public String createTicket(RuntimeContext ctx) {
        String userId = ctx.getUserId();
        TicketDraft draft = draftBox.get(userId).orElse(null);
        if (draft == null) {
            return "[建单失败] 当前没有待确认的工单草稿——请先用 draft_ticket 生成草稿卡并展示给员工确认";
        }
        Ticket created = store.create(draft, userId);
        draftBox.remove(userId);
        return "工单已创建：[" + created.id() + "] " + created.title()
                + "（分类：" + created.category() + "，优先级：" + created.priority()
                + "，状态：" + created.status() + "）\n"
                + "请把工单号告知员工，后续可用 query_ticket 查询进度。";
    }

    @Tool(name = "cancel_ticket_draft",
            description = "取消建单：丢弃当前待确认的工单草稿（员工放弃建单或改变主意时调用）",
            concurrencySafe = false)
    public String cancelTicketDraft(RuntimeContext ctx) {
        return draftBox.remove(ctx.getUserId()).isPresent()
                ? "工单草稿已取消（未落库）。"
                : "当前没有待取消的工单草稿。";
    }

    /** 分类归一化：词表外（模型输出“IT 权限”“网络连接”等变体或越界值）映射到最近词表项或“其他”。 */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "其他";
        }
        String trimmed = category.trim();
        return TicketDraft.CATEGORIES.stream()
                .filter(c -> trimmed.contains(c) || c.contains(trimmed))
                .findFirst()
                .orElse("其他");
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
