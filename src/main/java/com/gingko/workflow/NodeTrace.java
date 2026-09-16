package com.gingko.workflow;

/**
 * 节点执行轨迹（M6，FR-M6-03：流程节点执行轨迹可查询——走了哪些节点、每步耗时）。
 *
 * <p>轨迹是图模式相对 ReAct 自由循环的可观测增量：自由模式下「这条消息经历了什么」
 * 只能从工具调用日志反推；图模式下每个节点的进出都被显式记录，
 * 「该检索的检索了吗」「确认门过了吗」从推断变成断言。
 */
public record NodeTrace(String node, long elapsedMs, String summary) {

    /** 轨迹单行摘要（Main 每条消息后打印，日志可还原整条路径）。 */
    public static String renderPath(java.util.List<NodeTrace> trace) {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        for (NodeTrace t : trace) {
            if (!sb.isEmpty()) {
                sb.append(" → ");
            }
            sb.append(t.node()).append('(').append(t.elapsedMs()).append("ms)");
            total += t.elapsedMs();
        }
        sb.append(" ｜ 共 ").append(trace.size()).append(" 步 ").append(total).append("ms");
        return sb.toString();
    }
}
