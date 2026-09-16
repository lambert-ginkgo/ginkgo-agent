package com.gingko.workflow;

import com.gingko.intent.TicketIntent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图执行的全局状态（M6）：节点间传递数据的唯一载体。
 *
 * <p>类型化字段（意图/检索结果/最终答复）是服务台流程的已知契约；
 * 通用 {@link #set}/{@link #get} 留给节点返回的自定义更新——
 * 对齐 v1 文档 StateGraph 的 OverAllState 概念（键值状态在节点间流转），
 * 但不引入其 KeyStrategy 合并策略（MVP 场景键集合有限，直接覆盖合并足够）。
 */
public final class WorkflowState {

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final List<NodeTrace> trace = new ArrayList<>();

    // ---- 类型化契约（服务台流程已知键） ----

    /** 用户原始消息。 */
    public String input() {
        return (String) data.get("input");
    }

    /** 意图路由结果（intent_route 节点写入）。 */
    public TicketIntent.Intent intent() {
        return (TicketIntent.Intent) data.get("intent");
    }

    /** 意图分类依据（轨迹留痕用）。 */
    public String intentReason() {
        return (String) data.get("intentReason");
    }

    /** 最终给用户的答复文本（agent/模板节点写入）。 */
    public String reply() {
        return (String) data.get("reply");
    }

    /** 节点抛出的异常（图执行终止时记录，Main 兜底话术）。 */
    public Exception error() {
        return (Exception) data.get("error");
    }

    // ---- 通用读写（节点自定义键） ----

    public Object get(String key) {
        return data.get(key);
    }

    public void set(String key, Object value) {
        data.put(key, value);
    }

    /** 合并节点返回的状态更新（null 键值忽略，直接覆盖）。 */
    void merge(Map<String, Object> updates) {
        if (updates == null) {
            return;
        }
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            if (e.getKey() != null) {
                data.put(e.getKey(), e.getValue());
            }
        }
    }

    // ---- 轨迹（FR-M6-03） ----

    void record(NodeTrace step) {
        trace.add(step);
    }

    public List<NodeTrace> trace() {
        return List.copyOf(trace);
    }
}
