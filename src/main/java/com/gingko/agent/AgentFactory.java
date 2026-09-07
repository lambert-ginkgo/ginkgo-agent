package com.gingko.agent;

import com.gingko.config.AgentConfig;
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
 * <p>M3 会话记忆（E03）新增两处装配：
 * <ul>
 *   <li>sysPrompt 追问纪律与指代理解（FR-M3-01 / FR-M3-04）</li>
 *   <li>显式 CompactionConfig：20 条消息触发压缩、保留最近 6 条、
 *       摘要 prompt 追加关键实体保留清单（FR-M3-03，截断规则随文章公开）</li>
 * </ul>
 */
public final class AgentFactory {

    public static final String AGENT_NAME = "ginkgo-service-desk";

    private static final String SYS_PROMPT = "你是企业 IT 服务台智能体 ginkgo。用简洁的中文回答员工的 IT 求助；"
            + "涉及工单状态、工单列表的问题，先调用工具查询再回答，不要编造工单信息；不知道就说不知道。"
            + "结合对话上下文理解『它』『那个单子』等指代，定位到本会话之前提到的具体工单或问题；"
            + "员工描述信息不足时（如未提供工单号、问题现象不清），先用一句话追问关键信息，"
            + "不要带着猜测硬答；同一问题最多追问 2 次，仍无法补全就基于已有信息回答并说明还缺什么。";

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

    public static HarnessAgent build(AgentConfig config) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TicketTools(new MockTicketStore(), MockTicketStore.DEFAULT_USER));

        return HarnessAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(SYS_PROMPT)
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
