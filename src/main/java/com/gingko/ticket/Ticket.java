package com.gingko.ticket;

/**
 * 工单记录（M2 建立，M5 扩展建单字段）。
 * M2：查询返回项（标题、状态、处理人、创建时间）；
 * M5（FR-M5-02/04）：新增分类、优先级、摘要、对话上下文摘要。
 */
public record Ticket(
        String id,
        String title,
        String status,
        String assignee,
        String createdAt,
        String reporter,
        String category,
        String priority,
        String summary,
        String contextSummary) {

    /** 状态词表（PRD 数据模型：新建/处理中/待人工/已解决/关闭；E02 mock 沿用"待处理"）。 */
    public static final String STATUS_NEW = "新建";
    public static final String STATUS_IN_PROGRESS = "处理中";
    public static final String STATUS_PENDING = "待处理";
    public static final String STATUS_HUMAN = "待人工";
    public static final String STATUS_RESOLVED = "已解决";
    public static final String STATUS_CLOSED = "关闭";
}
