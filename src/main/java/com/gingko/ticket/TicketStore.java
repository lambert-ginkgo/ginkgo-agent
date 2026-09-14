package com.gingko.ticket;

import java.util.List;
import java.util.Optional;

/**
 * 工单数据源抽象（M2，E02）：mock 与后续真实存储共用同一接口，替换零改动（PRD M2 验收标准）。
 * M5（E05）新增 create：确认后的草稿落库（FR-M5-03）。
 */
public interface TicketStore {

    Optional<Ticket> findById(String id);

    List<Ticket> findByReporter(String reporter);

    /** 创建工单（M5）：由确认后的草稿生成，返回带工单号的完整工单。 */
    Ticket create(TicketDraft draft, String reporter);
}
