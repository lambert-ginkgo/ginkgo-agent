package com.gingko.ticket;

import java.util.List;
import java.util.Optional;

/**
 * mock 工单数据源（M2，E02）：内嵌测试数据。
 * E02 CLI 期“我”固定为 DEFAULT_USER（PRD FR-M2-02，E07 起由模拟身份提供）。
 */
public class MockTicketStore implements TicketStore {

    public static final String DEFAULT_USER = "cli-user";

    private static final List<Ticket> TICKETS = List.of(
            new Ticket("1024", "VPN 连不上", "处理中", "张工", "2026-09-01 09:20", DEFAULT_USER),
            new Ticket("1025", "Outlook 收不到邮件", "待处理", "李工", "2026-09-02 14:05", DEFAULT_USER),
            new Ticket("1026", "申请开通测试环境权限", "已解决", "王工", "2026-08-28 16:40", DEFAULT_USER),
            new Ticket("2001", "会议室投屏无信号", "处理中", "张工", "2026-09-03 10:15", "zhangsan"));

    @Override
    public Optional<Ticket> findById(String id) {
        return TICKETS.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public List<Ticket> findByReporter(String reporter) {
        return TICKETS.stream().filter(t -> t.reporter().equals(reporter)).toList();
    }
}
