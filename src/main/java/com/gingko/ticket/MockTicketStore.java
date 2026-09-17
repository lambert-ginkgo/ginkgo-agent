package com.gingko.ticket;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mock 工单数据源（M2 建立，M5 支持创建）。
 * E02 CLI 期“我”固定为 DEFAULT_USER（PRD FR-M2-02，E07 起由模拟身份提供）。
 * M5：存储从不可变 List 改为 CopyOnWriteArrayList（建单写入），工单号全局自增（当前最大号 2001 之后）。
 */
public class MockTicketStore implements TicketStore {

    public static final String DEFAULT_USER = "cli-user";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<Ticket> tickets = new CopyOnWriteArrayList<>(List.of(
            new Ticket("1024", "VPN 连不上", Ticket.STATUS_IN_PROGRESS, "张工", "2026-09-01 09:20", DEFAULT_USER,
                    "网络", "高", "办公区 VPN 无法连接，影响远程办公", null),
            new Ticket("1025", "Outlook 收不到邮件", Ticket.STATUS_PENDING, "李工", "2026-09-02 14:05", DEFAULT_USER,
                    "软件", "中", "Outlook 客户端收不到新邮件，发件正常", null),
            new Ticket("1026", "申请开通测试环境权限", Ticket.STATUS_RESOLVED, "王工", "2026-08-28 16:40", DEFAULT_USER,
                    "权限", "中", "申请测试环境只读权限用于联调验证", null),
            new Ticket("2001", "会议室投屏无信号", Ticket.STATUS_IN_PROGRESS, "张工", "2026-09-03 10:15", "zhangsan",
                    "硬件", "中", "三楼会议室投屏无信号，HDMI 线已更换仍未恢复", null)));

    private final AtomicInteger nextId = new AtomicInteger(2002);

    @Override
    public Optional<Ticket> findById(String id) {
        return tickets.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public List<Ticket> findByReporter(String reporter) {
        return tickets.stream().filter(t -> t.reporter().equals(reporter)).toList();
    }

    @Override
    public List<Ticket> findAll() {
        return List.copyOf(tickets);
    }

    @Override
    public Ticket create(TicketDraft draft, String reporter) {
        String id = String.valueOf(nextId.getAndIncrement());
        Ticket ticket = new Ticket(
                id,
                draft.title(),
                Ticket.STATUS_NEW,
                null,
                LocalDateTime.now().format(TIME_FMT),
                reporter,
                draft.category(),
                draft.priority(),
                draft.summary(),
                draft.contextSummary());
        tickets.add(ticket);
        return ticket;
    }

    /** 当前工单总数（验收断言用：建单前后对比）。 */
    public int size() {
        return tickets.size();
    }

    @Override
    public Optional<Ticket> updateStatus(String id, String status) {
        for (int i = 0; i < tickets.size(); i++) {
            Ticket t = tickets.get(i);
            if (t.id().equals(id)) {
                Ticket updated = new Ticket(t.id(), t.title(), status, t.assignee(), t.createdAt(),
                        t.reporter(), t.category(), t.priority(), t.summary(), t.contextSummary());
                tickets.set(i, updated);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }
}
