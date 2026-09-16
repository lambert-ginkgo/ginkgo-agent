package com.gingko.ticket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工单草稿箱（M6 从 TicketCreationTools 外提）：reporter → 待确认草稿。
 *
 * <p>外提原因（E06 流程编排的关键改造）：E05 时代草稿箱藏在
 * {@code TicketCreationTools} 内部，draft 与 create 是「模型自愿遵守的两阶段」
 * ——create_ticket 工具在场，靠 sysPrompt 与工具 description 双保险防止跳过确认。
 * E06 图模式把落库权从模型手里收走：create_ticket 不再注册给模型，
 * 落库只发生在图的 confirm 节点（代码控制的确认门）。草稿箱因此必须
 * 成为独立的共享组件——draft 工具（模型写入）与图 confirm 节点（代码读取落库）
 * 操作同一份状态。
 */
public final class TicketDraftBox {

    private final Map<String, TicketDraft> drafts = new ConcurrentHashMap<>();

    /** 暂存草稿（同一 reporter 同时只有一个，重新 draft 即替换——修改语义幂等）。 */
    public void put(String reporter, TicketDraft draft) {
        drafts.put(reporter, draft);
    }

    public Optional<TicketDraft> get(String reporter) {
        return Optional.ofNullable(drafts.get(reporter));
    }

    /** 取出并清空（confirm 落库 / cancel 取消时用）。 */
    public Optional<TicketDraft> remove(String reporter) {
        return Optional.ofNullable(drafts.remove(reporter));
    }

    /** 是否有待确认草稿（挂起判定 + 意图分类提示词切换的依据）。 */
    public boolean has(String reporter) {
        return drafts.containsKey(reporter);
    }
}
