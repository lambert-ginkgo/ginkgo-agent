package com.gingko.ticket;

/**
 * 工单记录（M2，E02）。字段即 PRD FR-M2-01 要求的返回项。
 */
public record Ticket(
        String id,
        String title,
        String status,
        String assignee,
        String createdAt,
        String reporter) {
}
