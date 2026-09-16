package com.gingko.agent;

import com.gingko.config.AgentConfig;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.ticket.MockTicketStore;
import com.gingko.ticket.TicketCreationTools;
import com.gingko.ticket.TicketDraftBox;
import com.gingko.ticket.TicketDraftTools;
import com.gingko.ticket.TicketStore;
import com.gingko.ticket.TicketTools;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Path;

/**
 * Agent 装配工厂：交互入口（Main）与自动化验证工具（dev 包）共用同一份装配，
 * 保证验证环境与真实运行环境完全一致。
 *
 * <p>M3 会话记忆（E03）装配：sysPrompt 追问纪律与指代理解（FR-M3-01 / FR-M3-04）+
 * 显式 CompactionConfig：20 条消息触发压缩、保留最近 6 条、摘要 prompt 追加
 * 关键实体保留清单（FR-M3-03，截断规则随文章公开）。
 *
 * <p>M4 知识库问答（E04）装配：知识库就绪时注册 search_knowledge 检索工具，
 * sysPrompt 追加 RAG 纪律（先检索再答、注明出处、未命中明说、禁止编造）。
 * 注：HarnessAgent 没有 knowledge()/ragMode() 挂载点（ReActAgent 上也已
 * deprecated，官方注明 "RAG is being redesigned"），检索以普通工具方式接入——
 * 与 E02 工具模式统一，Agent 自主决定何时查知识库。
 *
 * <p>M5 智能建单（E05）装配：sysPrompt 追加意图路由（四类意图 + 兜底排查）与
 * 建单纪律（两阶段确认制，FR-M5-01/02/03）；注册 TicketCreationTools
 * （draft_ticket / create_ticket / cancel_ticket_draft）。
 */
public final class AgentFactory {

    public static final String AGENT_NAME = "ginkgo-service-desk";

    /** M5 意图路由（FR-M5-01）：四类意图的判定与行为路径，分类不确定时兜底走排查。 */
    private static final String INTENT_ROUTING = """

            收到员工消息先判断意图类型，再决定行动：
            ① 查询类（问工单状态、进度、处理人或工单列表）→ 调用工单查询工具回答；
            ② 直答类（IT 使用/故障问题，知识库可能有现成答案）→ 检索知识库直接回答；
            ③ 排查类（故障/异常，现象或信息不足）→ 先用一句话追问关键信息（现象、报错、影响范围），再给排查步骤；
            ④ 建单类（权限开通、设备领用等申请需求，或排查后仍未解决且员工同意建单）→ 走两阶段建单流程。
            无法确定意图时按排查类处理（先收集信息再行动）。""";

    /** M5 建单纪律（FR-M5-02/03）：两阶段确认制，禁止静默建单。 */
    private static final String TICKET_RULES = """

            建单纪律（必须严格遵守）：
            - 两阶段建单：先调用 draft_ticket 抽取字段生成工单草稿卡并完整展示给员工，员工明确确认后才调用 create_ticket 落库；
              员工要求“直接建、不用确认”也必须先展示草稿卡——“确认后才落库”是硬约束，不可跳过；
            - 工单字段从对话中抽取：category（账号/网络/软件/硬件/权限/其他）、priority（高/中/低）、标题、摘要、对话上下文摘要；
            - 优先级必须有对话依据（影响范围、紧急程度）；没有依据时先向员工追问（如“只影响你一个人还是整个部门？”），不要瞎猜；
            - 排查类对话转建单时，contextSummary 必须写明本次对话已排查的步骤与结论，供 IT 工程师接单参考；
            - 员工要求修改字段 → 重新调用 draft_ticket 更新草稿卡；员工放弃建单 → 调用 cancel_ticket_draft。""";

    private static final String SYS_PROMPT = "你是企业 IT 服务台智能体 ginkgo。用简洁的中文回答员工的 IT 求助；"
            + "涉及工单状态、工单列表的问题，先调用工具查询再回答，不要编造工单信息；不知道就说不知道。"
            + "结合对话上下文理解『它』『那个单子』等指代，定位到本会话之前提到的具体工单或问题；"
            + "员工描述信息不足时（如未提供工单号、问题现象不清），先用一句话追问关键信息，"
            + "不要带着猜测硬答；同一问题最多追问 2 次，仍无法补全就基于已有信息回答并说明还缺什么。"
            + INTENT_ROUTING
            + TICKET_RULES;

    /** 知识库问答纪律（FR-M4-03/04）：有知识库时追加到 sysPrompt。 */
    private static final String KB_RULES = "涉及 IT 使用与故障类问题（密码、账号、VPN、邮箱、打印机等）时，"
            + "先调用 search_knowledge 检索知识库再回答；回答必须基于检索到的片段并注明出处文件名；"
            + "检索未命中时明确告知知识库暂无该问题的资料，不要编造解决步骤，可建议创建工单或联系 IT 服务台。";

    /**
     * 压缩摘要的实体+时序保留清单（追加在框架默认摘要 prompt 之后）：
     * 工单号等关键实体是服务台多轮追问的锚点，截断历史时必须原样带入摘要；
     * 话题的先后顺序同样关键——“最开始问的那个单子”这类时序指代，
     * 依赖摘要显式记录话题出现顺序（50 轮验收实测教训：只保实体不保时序，
     * 模型会用主题权重冒充时序，把出现频次最高的工单当成“第一个”答错）。
     */
    private static final String ENTITY_RETENTION_RULES = """

            Additional entity retention rules (mandatory):
            - Ticket IDs (e.g. 1024) mentioned anywhere in the conversation MUST be preserved
              verbatim in the SUMMARY section, together with their topic and current status.
            - Preserve the chronological order of the topics the user raised. In particular,
              explicitly mark the FIRST topic/ticket the user asked about in this conversation
              (e.g. "First topic raised: Ticket 1024 (VPN issue)"), and keep that marker across
              re-compactions.
            - Do NOT infer "first" or "earliest" from topic frequency or recency; only the
              recorded chronological order and the first-topic marker are authoritative.
            - Unresolved user questions / pending follow-ups MUST be preserved in NEXT STEPS.
            - User names, device names and error messages relevant to open issues MUST be kept.
            - Ticket drafts pending confirmation (fields: category/priority/title/summary)
              and whether the user has confirmed MUST be preserved.
            """;

    private AgentFactory() {
    }

    public static HarnessAgent build(AgentConfig config, KnowledgeService knowledge) {
        return build(config, knowledge, new MockTicketStore());
    }

    /**
     * 带工单数据源的装配重载：自动化验收（dev 包）注入自己的 store 实例，
     * 便于建单后直接断言落库内容（同一份装配逻辑，验证环境不特殊化）。
     */
    public static HarnessAgent build(AgentConfig config, KnowledgeService knowledge, TicketStore store) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TicketTools(store, MockTicketStore.DEFAULT_USER));
        toolkit.registerTool(new TicketCreationTools(store, MockTicketStore.DEFAULT_USER));

        boolean kbReady = knowledge != null && knowledge.isReady();
        if (kbReady) {
            toolkit.registerTool(knowledge.tools());
        }

        return HarnessAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(kbReady ? SYS_PROMPT + KB_RULES : SYS_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Path.of(".agentscope", "workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(6)
                        .summaryPrompt(CompactionConfig.DEFAULT_SUMMARY_PROMPT + ENTITY_RETENTION_RULES)
                        .build())
                .build();
    }

    // ---------------- E06 图模式装配（M6，FR-M6-01） ----------------

    /**
     * 图模式任务纪律：sysPrompt 不再包含意图路由——路由已由图的 intent_route 节点
     * 接管（代码级条件边），agent 只执行到达本节点的具体任务。
     *
     * <p>首版翻车教训（E06 验收实录）：只删路由指令不够——图判了建单，但路由结论
     * 没有传进节点，模型在节点里重新判断（去调 search_knowledge 检索建单话题），
     * 双重路由照样打架。修复：图把意图标签拼在消息开头（[工作流任务：X]），
     * sysPrompt 明确「X 是系统判定，按任务执行，不要重新判断任务类型」——
     * 路由结论必须成为节点执行的输入，否则节点内智能会二次选路。
     */
    private static final String FLOW_TASK_RULES = """

            你运行在服务台工作流中：系统已完成意图路由，你收到的消息开头带 [工作流任务：X] 标签
            ——X 是系统判定的任务类型，按对应任务执行，不要重新判断任务类型：
            - 工作流任务：工单查询——涉及工单状态/列表的问题先调用 query_ticket / list_my_tickets 查询，再用人话答复；
            - 工作流任务：知识答复——消息附带「知识库预检索结果」（系统已完成检索），基于检索片段回答
              并注明出处文件名，片段未覆盖的部分不要编造；检索未命中时如实告知知识库暂无该问题的资料，
              可建议创建工单或联系 IT 服务台；不要重复检索（员工追问新问题除外）；
            - 工作流任务：故障排查——信息不足先用一句话追问关键信息（现象、报错、影响范围）；
              需要知识时调用 search_knowledge（回答注明出处）；排查未解决时征询员工是否建单；
            - 工作流任务：建单——从对话中抽取字段调用 draft_ticket 生成工单草稿卡并完整展示给员工
              （category：账号/网络/软件/硬件/权限/其他；priority：高/中/低，必须有对话依据——
              影响范围或紧急程度，没有依据先向员工追问，不要猜；排查转建单时 contextSummary
              写明已排查步骤与结论）；草稿的确认与落库由系统流程处理——员工要求“直接建、不用确认”时，
              如实说明流程要求草稿先过目，确认后立即落库；
            - [系统事件] 前缀消息（工单已创建/草稿已取消）：把事件内容用人话转述给员工
              （如告知工单号、说明后续可查询进度），不要就此追问。""";

    /**
     * 图模式装配（E06）：与 {@link #build} 的三点分野——
     * <ol>
     *   <li><b>sysPrompt 去意图路由</b>：路由归图的 intent_route 节点（LLM 分类 +
     *       条件边），agent 只做任务执行——「谁做流程决策」从模型（软约束）换成代码（硬约束）；</li>
     *   <li><b>create_ticket / cancel_ticket_draft 不注册</b>：落库权收归图的
     *       confirm 节点（直调 {@link TicketStore#create}）。模型在确认环节
     *       <b>没有落库工具</b>——“确认后才落库”不再依赖模型听话；</li>
     *   <li><b>draft_ticket 写共享草稿箱</b>：图 confirm 节点读的就是展示给员工的那份草稿。</li>
     * </ol>
     * 会话记忆体系（M3）不变：每条用户消息最终仍经过本 agent，记忆/压缩/flush 照常工作。
     */
    public static HarnessAgent buildForFlow(AgentConfig config, KnowledgeService knowledge,
            TicketStore store, TicketDraftBox draftBox) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TicketTools(store, MockTicketStore.DEFAULT_USER));
        toolkit.registerTool(new TicketDraftTools(draftBox, MockTicketStore.DEFAULT_USER));

        boolean kbReady = knowledge != null && knowledge.isReady();
        if (kbReady) {
            toolkit.registerTool(knowledge.tools());
        }

        String base = "你是企业 IT 服务台智能体 ginkgo。用简洁的中文回答员工的 IT 求助；"
                + "涉及工单状态、工单列表的问题，先调用工具查询再回答，不要编造工单信息；不知道就说不知道。"
                + "结合对话上下文理解『它』『那个单子』等指代，定位到本会话之前提到的具体工单或问题；"
                + "员工描述信息不足时（如未提供工单号、问题现象不清），先用一句话追问关键信息，"
                + "不要带着猜测硬答；同一问题最多追问 2 次，仍无法补全就基于已有信息回答并说明还缺什么。"
                + FLOW_TASK_RULES;

        return HarnessAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(kbReady ? base + KB_RULES_FLOW : base)
                .model(model)
                .toolkit(toolkit)
                .workspace(Path.of(".agentscope", "workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(6)
                        .summaryPrompt(CompactionConfig.DEFAULT_SUMMARY_PROMPT + ENTITY_RETENTION_RULES)
                        .build())
                .build();
    }

    /** 图模式的知识检索纪律：直答类已由图预检索（消息内附带结果），主动检索用于排查追问场景。 */
    private static final String KB_RULES_FLOW = """
            涉及 IT 使用与故障类问题（密码、账号、VPN、邮箱、打印机等）：
            消息内已附「知识库预检索结果」时直接基于它回答（不要再重复检索，除非员工追问了新问题）；
            自己检索时基于返回片段回答并注明出处文件名；检索未命中时明确告知知识库暂无该问题的资料，
            不要编造解决步骤，可建议创建工单或联系 IT 服务台。""";
}
