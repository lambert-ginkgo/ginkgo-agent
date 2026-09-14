package com.gingko.ticket;

import java.util.List;

/**
 * 建单草稿（M5，FR-M5-02/03）：Agent 从对话中抽取的字段集合。
 *
 * <p>草稿 ≠ 工单：草稿只存在于确认前的“草稿箱”，员工确认后才由
 * {@link TicketStore#create} 落库。确认的对象与落库的对象必须是同一份数据
 * （员工看到的草稿卡 = create_ticket 落库的内容），杜绝“展示一份、落库另一份”
 * 的二次抽取漂移——这是“确认后才创建”的工程含义。
 */
public record TicketDraft(
        String category,
        String priority,
        String title,
        String summary,
        String contextSummary) {

    /** 受控分类词表（工具层校验，词表外归一化到“其他”）。 */
    public static final List<String> CATEGORIES = List.of("账号", "网络", "软件", "硬件", "权限", "其他");

    /** 优先级词表（FR-M5-02：高/中/低；无依据时工具层拒绝，倒逼模型先追问）。 */
    public static final List<String> PRIORITIES = List.of("高", "中", "低");

    /** 工单草稿卡（展示给员工确认/修改，FR-M5-03）。 */
    public String renderCard() {
        return """
                【工单草稿】尚未创建，等待你的确认
                ————————————————
                分类：%s
                优先级：%s
                标题：%s
                摘要：%s
                对话上下文：%s
                ————————————————
                确认无误请回复“确认”；需要修改请直接说明（例如：优先级改成高）。"""
                .formatted(
                        category,
                        priority,
                        title,
                        orPlaceholder(summary, "（未填写）"),
                        orPlaceholder(contextSummary, "（无排查记录）"));
    }

    private static String orPlaceholder(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value.trim();
    }
}
