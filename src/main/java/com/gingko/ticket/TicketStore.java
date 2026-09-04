package com.gingko.ticket;

import java.util.List;
import java.util.Optional;

/**
 * 工单数据源抽象（M2，E02）：mock 与后续真实存储共用同一接口，替换零改动（PRD M2 验收标准）。
 */
public interface TicketStore {

    Optional<Ticket> findById(String id);

    List<Ticket> findByReporter(String reporter);
}
