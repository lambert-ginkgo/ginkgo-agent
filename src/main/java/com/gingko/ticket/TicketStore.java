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

    /** 全量工单（M7）：IT 管理员的全量列表用，员工路径不可达（工具层权限拦截）。 */
    List<Ticket> findAll();

    /** 创建工单（M5）：由确认后的草稿生成，返回带工单号的完整工单。 */
    Ticket create(TicketDraft draft, String reporter);

    /** 更新工单状态（M7）：IT 管理员处理工单用，返回更新后的工单（不存在返回 empty）。 */
    Optional<Ticket> updateStatus(String id, String status);
}
