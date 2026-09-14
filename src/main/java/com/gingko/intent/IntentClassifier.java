package com.gingko.intent;

import com.gingko.config.AgentConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.time.Duration;
import java.util.UUID;

/**
 * 意图分类器（M5，FR-M5-01）：独立裸 {@link ReActAgent} + 结构化输出
 * {@code call(text, Class, RuntimeContext)}——与主对话（HarnessAgent，sysPrompt 路由）
 * 是同一套意图判定的两种落地。
 *
 * <p>定位差异（E05/E06 分界线）：主链路保持 ReAct 自主路由（意图规则写在 sysPrompt 里，
 * 模型边推理边决策），分类器是“一进一出”的独立步骤（输入一句话，输出强类型
 * {@link TicketIntent}）——后者是 E06 Graph 编排里“意图路由节点”的雏形，
 * E05 先用它做分类准确率的量化验收（{@code dev.M5AcceptanceRun}）。
 *
 * <p>结构化输出机制（2.0.3 源码口径）：OpenAIChatModel 未覆写
 * {@code supportsNativeStructuredOutput()}（默认 false），DeepSeek 无原生
 * json_schema response format——框架自动走合成工具路径：临时注入
 * generate_response 工具 + TOOL_CHOICE 强制调用，结果解析进
 * {@code Msg.getStructuredData(Class)}。
 *
 * <p>兜底（FR-M5-01“分类错误有兜底，默认走排查”）：模型输出非法枚举、
 * 结构化数据缺失或解析异常时，一律落到 TROUBLESHOOT。
 */
public class IntentClassifier implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private static final String SYS_PROMPT = """
            你是企业 IT 服务台的消息意图分类器。判断员工消息属于以下哪类意图：
            - QUERY_TICKET：查询已有工单的状态、进度、处理人或工单列表
            - DIRECT_ANSWER：IT 使用/故障类问题，知识库文档可能直接有现成答案（如密码重置步骤、邮箱配置方法）
            - TROUBLESHOOT：故障/异常类问题，需要交互式排查（如 VPN 连不上、软件报错、设备异常）
            - CREATE_TICKET：申请/变更类需求（如开通权限、领用设备），或员工明确要求创建工单
            判定规则：无法确定意图时输出 TROUBLESHOOT（兜底）；非 IT 领域的闲聊也输出 TROUBLESHOOT。
            只做分类，不解决问题。""";

    private final ReActAgent agent;

    private IntentClassifier(ReActAgent agent) {
        this.agent = agent;
    }

    public static IntentClassifier create(AgentConfig config) {
        // stream(false)：分类器不需要打字机效果，只要最终结构化结果
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(false)
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("ginkgo-intent-classifier")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .build();
        return new IntentClassifier(agent);
    }

    /**
     * 分类一条员工消息。
     *
     * @return 分类结果 + 是否来自模型输出（false = 兜底生效）
     */
    public Classified classify(String text) {
        // 每条消息独立会话：分类是无状态判定，分类历史不应互相污染
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("intent-classifier")
                .sessionId("cls-" + UUID.randomUUID())
                .build();
        try {
            Msg reply = agent.call(text, TicketIntent.class, ctx).block(TIMEOUT);
            if (reply != null && reply.hasStructuredData()) {
                TicketIntent ti = reply.getStructuredData(TicketIntent.class);
                if (ti != null && ti.intent() != null) {
                    return new Classified(ti.intent(), ti.reason(), true);
                }
                return fallback("结构化数据存在但 intent 为空");
            }
            return fallback("结构化数据缺失（响应文本："
                    + truncate(reply == null ? "null" : reply.getTextContent()) + "）");
        } catch (Exception e) {
            return fallback("解析异常：" + e.getMessage());
        }
    }

    @Override
    public void close() {
        agent.close();
    }

    /** 分类结果（fromModel=false 表示兜底生效，可统计“模型输出不可解析”的比例）。 */
    public record Classified(TicketIntent.Intent intent, String reason, boolean fromModel) {
    }

    private static Classified fallback(String why) {
        return new Classified(TicketIntent.Intent.TROUBLESHOOT, why, false);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }
}
