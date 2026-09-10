package com.gingko.agent;

import com.gingko.config.AgentConfig;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.ticket.MockTicketStore;
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
 */
public final class AgentFactory {

    public static final String AGENT_NAME = "ginkgo-service-desk";

    private static final String SYS_PROMPT = "你是企业 IT 服务台智能体 ginkgo。用简洁的中文回答员工的 IT 求助；"
            + "涉及工单状态、工单列表的问题，先调用工具查询再回答，不要编造工单信息；不知道就说不知道。"
            + "结合对话上下文理解『它』『那个单子』等指代，定位到本会话之前提到的具体工单或问题；"
            + "员工描述信息不足时（如未提供工单号、问题现象不清），先用一句话追问关键信息，"
            + "不要带着猜测硬答；同一问题最多追问 2 次，仍无法补全就基于已有信息回答并说明还缺什么。";

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
            """;

    private AgentFactory() {
    }

    public static HarnessAgent build(AgentConfig config, KnowledgeService knowledge) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TicketTools(new MockTicketStore(), MockTicketStore.DEFAULT_USER));

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
}
