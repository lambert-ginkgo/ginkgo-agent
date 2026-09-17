package com.gingko.flow;

import com.gingko.agent.AgentFactory;
import com.gingko.auth.User;
import com.gingko.auth.UserDirectory;
import com.gingko.config.AgentConfig;
import com.gingko.intent.IntentClassifier;
import com.gingko.intent.IntentClassifier.Classified;
import com.gingko.intent.TicketIntent;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.ticket.AdminTicketTools;
import com.gingko.ticket.MockTicketStore;
import com.gingko.ticket.Ticket;
import com.gingko.ticket.TicketDraft;
import com.gingko.ticket.TicketDraftBox;
import com.gingko.ticket.TicketStore;
import com.gingko.workflow.NodeTrace;
import com.gingko.workflow.WorkflowGraph;
import com.gingko.workflow.WorkflowState;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 服务台工作流（M6，FR-M6-01/02/03）：把「意图识别 → 检索/建单 → 答复 → 升级」
 * 固化为确定性图编排。
 *
 * <p>图结构（节点名即流程文档，轨迹可还原整条路径）：
 * <pre>
 * START → intent_route ─┬─ QUERY_TICKET   → agent_reply ────────────────→ END
 *                        ├─ DIRECT_ANSWER  → kb_presearch → agent_reply → END
 *                        ├─ TROUBLESHOOT   → agent_reply ────────────────→ END
 *                        ├─ CREATE_TICKET  → agent_draft ────────────────→ END（挂起：草稿箱非空）
 *                        ├─ CONFIRM_DRAFT  → confirm_create → agent_notify → END
 *                        └─ CANCEL_DRAFT   → cancel_draft  → agent_notify → END
 * </pre>
 *
 * <p>E05 → E06 的确定性升级（同一流程的两种实现）：
 * <ul>
 *   <li><b>意图路由</b>：sysPrompt 指令（模型每轮自主判断，软约束）→ 图条件边
 *       （intent_route 节点结构化输出 + switch 路由，分类错误有 TROUBLESHOOT 兜底支路，
 *       流程永远不会无路可走——无死循环的结构性保证）；</li>
 *   <li><b>必检索</b>：直答支路先走 {@code kb_presearch}（Java 直调 {@link KnowledgeService}，
 *       检索结果拼进 agent 消息）——E04 时代靠 sysPrompt「先检索再答」的模型自觉；</li>
 *   <li><b>确认门</b>：create_ticket 工具不注册（见 {@link AgentFactory#buildForFlow}），
 *       落库只发生在 confirm_create 节点（员工消息被分类为 CONFIRM_DRAFT 才可达）——
 *       E05 的反静默建单靠 prompt 纪律，E06 靠「想落库也没有工具」。</li>
 * </ul>
 *
 * <p>挂起-恢复（两阶段建单的图语义）：agent_draft 执行后图结束，草稿箱非空即业务挂起态；
 * 下一条消息由 {@code classify(text, pendingDraft)} 挂起感知分类（六类）——
 * 确认走 confirm_create，取消走 cancel_draft，修改字段/新话题按四类正常路由
 * （修改 = CREATE_TICKET → agent_draft 重新抽取覆盖，幂等；多话题并行不受草稿阻塞）。
 *
 * <p>升级节点（escalate）：M6 预留——知识未命中时 agent 按纪律建议建单或转人工话术，
 * 显式的 escalate 节点随 M8（E08）落地。
 */
public final class ServiceDeskFlow implements AutoCloseable {

    private final HarnessAgent agent;
    private final IntentClassifier classifier;
    private final KnowledgeService knowledge;
    private final TicketStore store;
    private final TicketDraftBox draftBox;
    private final WorkflowGraph graph;
    /** per-user 上轮意图（会话连续性路由的锚点：多轮补充消息的分类提示）。 */
    private final Map<String, TicketIntent.Intent> lastIntents = new ConcurrentHashMap<>();

    private ServiceDeskFlow(HarnessAgent agent, IntentClassifier classifier, KnowledgeService knowledge,
            TicketStore store, TicketDraftBox draftBox) {
        this.agent = agent;
        this.classifier = classifier;
        this.knowledge = knowledge;
        this.store = store;
        this.draftBox = draftBox;
        this.graph = buildGraph();
    }

    /** 标准装配（Main 使用）：mock 数据源。 */
    public static ServiceDeskFlow create(AgentConfig config, KnowledgeService knowledge) {
        return create(config, knowledge, new MockTicketStore());
    }

    /** 验收装配重载（dev 包注入自己的 store，验证环境与运行环境同一份装配逻辑）。 */
    public static ServiceDeskFlow create(AgentConfig config, KnowledgeService knowledge, TicketStore store) {
        TicketDraftBox draftBox = new TicketDraftBox();
        HarnessAgent agent = AgentFactory.buildForFlow(config, knowledge, store, draftBox);
        IntentClassifier classifier = IntentClassifier.create(config);
        return new ServiceDeskFlow(agent, classifier, knowledge, store, draftBox);
    }

    /**
     * 处理一条员工消息：意图路由（挂起感知 + 会话上下文）→ 图执行 → 返回答复与轨迹。
     *
     * <p>M7（E07）：业务身份从会话上下文取（{@code ctx.getUserId()}）——
     * 草稿箱分桶、确认落库的 reporter、挂起判定全部按当次会话用户，
     * 不再是构造时固定的默认用户（E06 及之前 /user 切换只切记忆、
     * 草稿箱和落库仍挂在默认用户上的错位在本集收口）。
     *
     * @param eventPrinter 流式事件打印回调（打字机效果，Main 传入；传 null 则不打印）
     */
    public FlowResult dispatch(String input, RuntimeContext ctx, Consumer<AgentEvent> eventPrinter) {
        String userId = ctx.getUserId();
        WorkflowState state = new WorkflowState();
        state.set("input", input);
        state.set("ctx", ctx);
        state.set("printer", eventPrinter);
        state.set("pendingDraft", draftBox.has(userId));
        state.set("lastIntent", lastIntents.get(userId));

        graph.run(state);

        // 本条消息的意图成为下一条的上轮意图（会话连续性路由锚点）
        if (state.intent() != null) {
            lastIntents.put(userId, state.intent());
        }
        List<NodeTrace> trace = state.trace();
        if (state.error() != null) {
            System.err.println("[flow] 执行失败于 " + (trace.isEmpty() ? "?" : trace.get(trace.size() - 1).node())
                    + "：" + state.error().getMessage());
        }
        return new FlowResult(state.reply(), trace, state.intent(), state.intentReason(), state.error());
    }

    /** 图执行结果（Main 打印与验收断言共用）。 */
    public record FlowResult(String reply, List<NodeTrace> trace, TicketIntent.Intent intent,
            String intentReason, Exception error) {

        /** 轨迹节点名序列（如 [intent_route, kb_presearch, agent_reply]，验收断言支路命中用）。 */
        public List<String> path() {
            return trace.stream().map(NodeTrace::node).toList();
        }
    }

    /** 待确认草稿是否存在（Main 展示挂起状态用；M7 起按会话用户判定）。 */
    public boolean hasPendingDraft(String userId) {
        return draftBox.has(userId);
    }

    /** 清空指定用户的待确认草稿与上轮意图（验收用例间隔离用）。 */
    public void clearPendingDraft(String userId) {
        draftBox.remove(userId);
        lastIntents.remove(userId);
    }

    /**
     * 切换会话的权限模式（M7，E07 透传 {@link HarnessAgent#setPermissionMode}）：
     * EXPLORE 为官方引擎的只读模式——readOnly=true 的工具自动放行、写操作 DENY，
     * {@code @Tool(readOnly = true)} 注解在 2.0.3 的唯一判定入口（三源核实结论）。
     */
    public void setPermissionMode(RuntimeContext ctx, io.agentscope.core.permission.PermissionMode mode) {
        agent.setPermissionMode(ctx, mode);
    }

    /** 当前会话的权限模式（/perm 命令回显用；注：2.0.3 的 get 只有 (userId, sessionId) 两参版，
     * 与 set 的 RuntimeContext 重载不对称——文档不写的差异，编译期才发现）。 */
    public io.agentscope.core.permission.PermissionMode getPermissionMode(RuntimeContext ctx) {
        return agent.getPermissionMode(ctx.getUserId(), ctx.getSessionId());
    }

    // ---------------- 图定义 ----------------

    private WorkflowGraph buildGraph() {
        return WorkflowGraph.builder()
                .addNode("intent_route", this::routeIntent)
                .addNode("kb_presearch", this::presearchKnowledge)
                .addNode("agent_reply", st -> runAgent(st, false))
                .addNode("agent_draft", st -> runAgent(st, false))
                .addNode("confirm_create", this::confirmCreate)
                .addNode("cancel_draft", this::cancelDraft)
                .addNode("agent_notify", st -> runAgent(st, true))
                .addEdge(WorkflowGraph.START, "intent_route")
                .addConditionalEdge("intent_route", this::routeByIntent)
                .addEdge("kb_presearch", "agent_reply")
                .addEdge("agent_reply", WorkflowGraph.END)
                .addEdge("agent_draft", WorkflowGraph.END)
                .addEdge("confirm_create", "agent_notify")
                .addEdge("cancel_draft", "agent_notify")
                .addEdge("agent_notify", WorkflowGraph.END)
                .compile();
    }

    /** 意图路由节点：挂起感知 + 会话上下文分类（LLM，结构化输出；失败兜底 TROUBLESHOOT——图的结构性保证）。 */
    private Map<String, Object> routeIntent(WorkflowState st) {
        boolean pending = Boolean.TRUE.equals(st.get("pendingDraft"));
        @SuppressWarnings("unchecked")
        TicketIntent.Intent last = (TicketIntent.Intent) st.get("lastIntent");
        Classified c = classifier.classify(st.input(), new IntentClassifier.RoutingContext(pending, last));
        return Map.of("intent", (Object) c.intent(), "intentReason", c.reason());
    }

    /** 意图条件边：六类意图 → 六条支路（TROUBLESHOOT 为兜底支路，永不悬空）。 */
    private String routeByIntent(WorkflowState st) {
        RuntimeContext ctx = (RuntimeContext) st.get("ctx");
        TicketIntent.Intent intent = st.intent();
        if (intent == null) {
            return "agent_reply";
        }
        return switch (intent) {
            case QUERY_TICKET, TROUBLESHOOT -> "agent_reply";
            case DIRECT_ANSWER -> "kb_presearch";
            case CREATE_TICKET -> "agent_draft";
            case CONFIRM_DRAFT -> draftBox.has(ctx.getUserId()) ? "confirm_create" : "agent_reply";
            case CANCEL_DRAFT -> draftBox.has(ctx.getUserId()) ? "cancel_draft" : "agent_reply";
        };
    }

    /**
     * 知识预检索节点（直答支路的确定性保证）：Java 直调 {@link KnowledgeService}，
     * 用户原话作为 query（E04 时代 ReAct 会自行改写 query，此处原话直检——差异可观测）。
     * 检索结果拼进 agent 消息——「先检索再答」从模型自觉变成代码必经。
     */
    private Map<String, Object> presearchKnowledge(WorkflowState st) {
        if (knowledge == null || !knowledge.isReady()) {
            return Map.of("kbPrefetch", (Object) "[知识库预检索结果] 知识库当前不可用（未配置或导入失败）。"
                    + "请如实告知员工知识库暂不可用，可建议创建工单或联系 IT 服务台。");
        }
        List<io.agentscope.core.rag.model.Document> hits;
        try {
            hits = knowledge.search(st.input());
        } catch (RuntimeException e) {
            return Map.of("kbPrefetch", (Object) "[知识库预检索结果] 检索失败（" + e.getMessage()
                    + "）。请告知员工知识库服务暂时不可用，稍后再试，或直接创建工单。");
        }
        if (hits == null || hits.isEmpty()) {
            return Map.of("kbPrefetch", (Object) "[知识库预检索结果] 未命中：知识库中没有检索到与该问题相关"
                    + "的内容（最高相似度低于阈值）。请如实告知员工知识库暂无该问题的资料，不要编造解决步骤，"
                    + "可建议创建工单或联系 IT 服务台。");
        }
        StringBuilder sb = new StringBuilder("[知识库预检索结果] 系统已完成检索，命中 ")
                .append(hits.size()).append(" 条片段（回答必须注明出处文件名，未覆盖部分不要编造）：\n");
        for (int i = 0; i < hits.size(); i++) {
            io.agentscope.core.rag.model.Document doc = hits.get(i);
            String filename = String.valueOf(doc.getPayloadValue("filename"));
            sb.append("\n[").append(i + 1).append("] 来源：").append(filename)
                    .append("（相似度 ").append(String.format("%.3f", doc.getScore())).append("）\n")
                    .append(doc.getMetadata().getContentText().strip()).append("\n");
        }
        return Map.of("kbPrefetch", (Object) sb.toString());
    }

    /**
     * agent 节点（agent_reply / agent_draft / agent_notify 共用实现）：
     * 主 {@link HarnessAgent} 流式调用——会话记忆体系（M3）照常工作（每条消息最终都过主 agent）。
     *
     * @param systemEvent true 表示处理系统事件转述（confirm/cancel 后的落库/取消结果），
     *                    消息前缀「[系统事件]」，agent 按转述纪律生成人话答复（记忆连续：下轮还能答“刚才建的单子”）
     */
    private Map<String, Object> runAgent(WorkflowState st, boolean systemEvent) {
        RuntimeContext ctx = (RuntimeContext) st.get("ctx");
        @SuppressWarnings("unchecked")
        Consumer<AgentEvent> printer = (Consumer<AgentEvent>) st.get("printer");

        StringBuilder reply = new StringBuilder();
        String message = composeAgentMessage(st, systemEvent);
        agent.streamEvents(new UserMessage(message), ctx)
                .doOnNext(event -> {
                    if (event.getType() == io.agentscope.core.event.AgentEventType.TEXT_BLOCK_DELTA) {
                        reply.append(((TextBlockDeltaEvent) event).getDelta());
                    }
                    if (printer != null) {
                        printer.accept(event);
                    }
                })
                .blockLast();
        return Map.of("reply", (Object) reply.toString());
    }

    /**
     * agent 消息组装：图的路由结论必须随消息下发（任务标签），节点内智能不再二次选路。
     * M7（E07）：消息头携带会话身份标签——agent 知道"和谁说话"（FR-M7-01），
     * 权限话术转述可以带上身份（如"你当前是员工身份"）；真正的权限判定不依赖这个标签
     * （工具层只认 RuntimeContext），标签仅是告知，伪造不了权限。
     * 直答支路再拼预检索结果；系统事件加前缀。
     */
    private static String composeAgentMessage(WorkflowState st, boolean systemEvent) {
        if (systemEvent) {
            return "[系统事件] " + st.get("systemEvent");
        }
        RuntimeContext ctx = (RuntimeContext) st.get("ctx");
        User user = UserDirectory.resolve(ctx.getUserId());
        String base = "[当前身份：" + user.display() + "] "
                + "[" + taskLabelOf(st.intent()) + "] " + st.input();
        Object prefetch = st.get("kbPrefetch");
        if (prefetch instanceof String kb && !kb.isBlank()) {
            return base + "\n\n---\n" + kb;
        }
        return base;
    }

    /** 意图 → 任务标签（图判定的任务类型，随消息传给 agent 节点）。 */
    private static String taskLabelOf(TicketIntent.Intent intent) {
        if (intent == null) {
            return "工作流任务：故障排查";
        }
        return switch (intent) {
            case QUERY_TICKET -> "工作流任务：工单查询";
            case DIRECT_ANSWER -> "工作流任务：知识答复";
            case TROUBLESHOOT -> "工作流任务：故障排查";
            case CREATE_TICKET, CONFIRM_DRAFT, CANCEL_DRAFT -> "工作流任务：建单";
        };
    }

    /** 确认落库节点（确认门的代码侧）：草稿箱取出 → {@link TicketStore#create} 落库 → 系统事件交 agent 转述。 */
    private Map<String, Object> confirmCreate(WorkflowState st) {
        RuntimeContext ctx = (RuntimeContext) st.get("ctx");
        TicketDraft draft = draftBox.remove(ctx.getUserId()).orElse(null);
        if (draft == null) {
            // 结构上不可达（条件边已校验草稿存在），防御性兜底：交 agent 自由答复
            return Map.of("systemEvent", (Object) "员工想确认建单，但待确认草稿已不存在（可能已被处理）。"
                    + "请向员工说明当前没有待确认的草稿，如需建单请重新描述需求。");
        }
        Ticket created = store.create(draft, ctx.getUserId());
        return Map.of("systemEvent", (Object) "工单已创建：[" + created.id() + "] " + created.title()
                + "（分类：" + created.category() + "，优先级：" + created.priority()
                + "，状态：" + created.status() + "）。请把工单号告知员工，"
                + "并简要说明后续可用工单号查询进度。");
    }

    /** 取消草稿节点：清空草稿箱 → 系统事件交 agent 转述。 */
    private Map<String, Object> cancelDraft(WorkflowState st) {
        RuntimeContext ctx = (RuntimeContext) st.get("ctx");
        draftBox.remove(ctx.getUserId());
        return Map.of("systemEvent", (Object) "员工取消了建单，待确认的工单草稿已丢弃（未落库）。"
                + "请向员工确认已取消，之后需要建单随时可以说。");
    }

    @Override
    public void close() {
        classifier.close();
        agent.close();
    }
}
