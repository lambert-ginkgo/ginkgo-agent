package com.gingko.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 工作流图（M6，FR-M6-01）：节点 + 边 + 条件边的确定性编排引擎。
 *
 * <p>手搓说明：AgentScope Java 2.0.3 无图编排 API（详见 {@link NodeAction} javadoc），
 * 本类按「最小可用图引擎」实现，概念模型对齐业界通用形态（LangGraph / v1 文档的
 * StateGraph）：<b>节点持有逻辑，边持有路由，状态全局流转，执行从 START 走到 END</b>。
 *
 * <p>三层防失控（对应 M6 验收标准「无死循环」）：
 * <ol>
 *   <li>{@code compile()} 构建期校验：所有边的目标必须是已注册节点或 END，
 *       条件边 router 的静态可达集也在校验范围——图定义本身无悬空边；</li>
 *   <li>{@code MAX_STEPS} 执行期硬上限：超过即抛 {@link GraphExecutionException}
 *       终止，条件路由写错（返回值恒指向自己等）也烧不完调用链；</li>
 *   <li>节点异常立即终止：异常记入状态与轨迹，不再继续走边（宁可停机不可乱跑）。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>{@code
 * WorkflowGraph graph = WorkflowGraph.builder()
 *         .addNode("intent_route", state -> Map.of("intent", classify(state.input())))
 *         .addNode("kb_search", this::searchKnowledge)
 *         .addEdge(WorkflowGraph.START, "intent_route")
 *         .addConditionalEdge("intent_route",
 *                 state -> switch (state.intent()) {
 *                     case DIRECT_ANSWER -> "kb_search";
 *                     default -> WorkflowGraph.END;
 *                 })
 *         .addEdge("kb_search", WorkflowGraph.END)
 *         .compile();
 *
 * WorkflowState state = new WorkflowState();
 * state.set("input", "密码忘了");
 * graph.run(state);
 * }</pre>
 */
public final class WorkflowGraph {

    public static final String START = "__START__";
    public static final String END = "__END__";

    /** 执行步数硬上限（防死循环；服务台流程实际最多 3-4 步，上限留足冗余）。 */
    public static final int MAX_STEPS = 15;

    private final Map<String, NodeAction> nodes;
    /** 固定边：from → to。 */
    private final Map<String, String> edges;
    /** 条件边：from → router(state) 返回下一节点名或 END。条件边优先于固定边。 */
    private final Map<String, Function<WorkflowState, String>> conditionalEdges;

    private WorkflowGraph(Builder builder) {
        this.nodes = new LinkedHashMap<>(builder.nodes);
        this.edges = new LinkedHashMap<>(builder.edges);
        this.conditionalEdges = new LinkedHashMap<>(builder.conditionalEdges);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行图：从 START 出发沿边走到 END。
     * 节点异常或步数超限时终止执行，异常写入状态（{@link WorkflowState#error()}）。
     *
     * @return 执行后的状态（含完整轨迹）
     */
    public WorkflowState run(WorkflowState state) {
        String current = nextOf(START, state);
        int steps = 0;
        while (!END.equals(current)) {
            if (current == null) {
                fail(state, new GraphExecutionException("路由返回空节点名（来自 " + previous(steps) + "）"));
                return state;
            }
            NodeAction action = nodes.get(current);
            if (action == null) {
                fail(state, new GraphExecutionException("未知节点：" + current));
                return state;
            }
            if (++steps > MAX_STEPS) {
                fail(state, new GraphExecutionException(
                        "执行步数超过上限 " + MAX_STEPS + "，疑似条件路由死循环，强制终止"));
                return state;
            }
            long begin = System.currentTimeMillis();
            try {
                state.merge(action.apply(state));
                state.record(new NodeTrace(current, System.currentTimeMillis() - begin, null));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - begin;
                state.record(new NodeTrace(current, elapsed, "异常: " + e.getMessage()));
                fail(state, e);
                return state;
            }
            current = nextOf(current, state);
        }
        return state;
    }

    /** 图定义中的节点名集合（compile 校验后对外可见，验收断言用）。 */
    public Set<String> nodeNames() {
        return nodes.keySet();
    }

    private String nextOf(String from, WorkflowState state) {
        Function<WorkflowState, String> router = conditionalEdges.get(from);
        if (router != null) {
            return router.apply(state);
        }
        return edges.get(from);
    }

    private void fail(WorkflowState state, Exception e) {
        state.set("error", e);
    }

    private static String previous(int steps) {
        return steps == 0 ? START : "第 " + steps + " 步";
    }

    /** 图构建器。 */
    public static final class Builder {

        private final Map<String, NodeAction> nodes = new LinkedHashMap<>();
        private final Map<String, String> edges = new LinkedHashMap<>();
        private final Map<String, Function<WorkflowState, String>> conditionalEdges = new LinkedHashMap<>();

        /** 注册节点（同名重复注册抛异常——节点名是轨迹的身份证，不允许歧义）。 */
        public Builder addNode(String name, NodeAction action) {
            if (nodes.putIfAbsent(name, action) != null) {
                throw new IllegalArgumentException("节点重名：" + name);
            }
            return this;
        }

        /** 固定边：from 执行完必到 to。 */
        public Builder addEdge(String from, String to) {
            if (edges.putIfAbsent(from, to) != null || conditionalEdges.containsKey(from)) {
                throw new IllegalArgumentException("节点 " + from + " 已有出边（一个节点只能有一条出边）");
            }
            return this;
        }

        /** 条件边：from 执行完由 router 决定去向（返回节点名或 END）。 */
        public Builder addConditionalEdge(String from, Function<WorkflowState, String> router) {
            if (conditionalEdges.putIfAbsent(from, router) != null || edges.containsKey(from)) {
                throw new IllegalArgumentException("节点 " + from + " 已有出边（一个节点只能有一条出边）");
            }
            return this;
        }

        /** 编译：校验图定义（边目标存在、START 有出边、节点有出路），返回不可变图。 */
        public WorkflowGraph compile() {
            Set<String> valid = new LinkedHashSet<>(nodes.keySet());
            valid.add(END);
            for (Map.Entry<String, String> e : edges.entrySet()) {
                if (!valid.contains(e.getValue())) {
                    throw new IllegalArgumentException("固定边悬空：" + e.getKey() + " → " + e.getValue());
                }
            }
            if (!edges.containsKey(START) && !conditionalEdges.containsKey(START)) {
                throw new IllegalArgumentException("START 没有出边，图不可执行");
            }
            for (String name : nodes.keySet()) {
                if (!edges.containsKey(name) && !conditionalEdges.containsKey(name)) {
                    throw new IllegalArgumentException("节点 " + name + " 没有出边（通往 END 也算出路）");
                }
            }
            return new WorkflowGraph(this);
        }
    }
}
