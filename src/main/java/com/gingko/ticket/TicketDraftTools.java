package com.gingko.ticket;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 工单草稿工具（M6，图模式装配）：只提供 {@code draft_ticket}。
 *
 * <p>与 E05 的 {@link TicketCreationTools} 的分野——图模式把建单流程切成
 * 「模型抽取（本工具）→ 代码确认门（图 confirm 节点直调 {@link TicketStore#create}）」：
 * <ul>
 *   <li>{@code create_ticket} 不注册：模型在确认环节<b>无落库能力</b>，
 *       「员工确认后才落库」从 prompt 纪律（软约束）变成工具不在场（硬约束）——
 *       E05 的反静默建单靠模型听话，E06 靠它想不听话都没有工具；</li>
 *   <li>草稿写入共享 {@link TicketDraftBox}：确认门读的就是模型展示给员工的那份草稿，
 *       「看到的」与「落库的」是同一份数据（E05 语义保持）。</li>
 * </ul>
 */
public class TicketDraftTools {

    private final TicketDraftBox draftBox;
    private final String currentUser;

    public TicketDraftTools(TicketDraftBox draftBox, String currentUser) {
        this.draftBox = draftBox;
        this.currentUser = currentUser;
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
                            + "排查类对话转建单时必填，无排查过程传空字符串") String contextSummary) {
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
        draftBox.put(currentUser, draft);
        return "工单草稿已生成（未落库，等待员工确认）：\n\n" + draft.renderCard();
    }

    /** 分类归一化：词表外映射到最近词表项或"其他"（与 TicketCreationTools 同规则）。 */
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
