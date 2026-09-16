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
            - CONFIRM_DRAFT：仅当系统提示存在待确认的工单草稿时使用——员工对草稿给出肯定答复（如"确认""没问题""就这样建吧"）
            - CANCEL_DRAFT：仅当系统提示存在待确认的工单草稿时使用——员工明确放弃建单（如"算了""先不建了""取消"）
            判定规则：无法确定意图时输出 TROUBLESHOOT（兜底）；非 IT 领域的闲聊也输出 TROUBLESHOOT。
            没有待确认草稿的系统提示时，CONFIRM_DRAFT 与 CANCEL_DRAFT 不可选；修改草稿字段
            （如"优先级改成高"）不算确认也不算取消，按四类正常分类（通常为 CREATE_TICKET）。
            只做分类，不解决问题。""";

    /** 挂起提示前缀（E06）：拼在用户消息前，告知分类器当前存在待确认草稿。 */
    private static final String PENDING_DRAFT_HINT =
            "[系统提示：当前有一份待确认的工单草稿。若这句话是对草稿的确认请输出 CONFIRM_DRAFT，"
                    + "明确放弃建单请输出 CANCEL_DRAFT；否则按四类正常分类。]\n\n员工消息：";

    /**
     * 会话上下文提示（E06 节三验收翻车修复）：无状态分类器看的是孤立消息——
     * 建单流程中员工补充的「公共空间，就我一个人用，今天就要」脱离上下文就是闲聊，
     * 被兜底判成 TROUBLESHOOT，图跳去排查支路（验收日志实证）。带上轮意图后，
     * 「补充信息」与「新话题」有了判定锚点。逐条路由 vs 上下文路由的差异由此显形。
     */
    private static final String CONTEXT_HINT_TEMPLATE =
            "[系统提示：这是多轮对话的后续消息，上一条员工消息的意图是 %s。"
                    + "若这句话是对上一话题的补充或回答（如补信息、答追问），保持同类意图；"
                    + "若明显开启新话题，按四类正常分类。]\n\n员工消息：";

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
     * 分类一条员工消息（E05 语义：四类路由，M5 验收与自由模式使用）。
     *
     * @return 分类结果 + 是否来自模型输出（false = 兜底生效）
     */
    public Classified classify(String text) {
        return classify(text, new RoutingContext(false, null));
    }

    /**
     * 路由上下文（E06）：挂起态（待确认草稿）与会话连续性（上轮意图）。
     * 挂起态解锁 CONFIRM_DRAFT / CANCEL_DRAFT；上轮意图锚定多轮补充消息的分类。
     */
    public record RoutingContext(boolean pendingDraft, TicketIntent.Intent lastIntent) {
    }

    /**
     * 分类一条员工消息（E06 图模式入口：挂起感知 + 会话上下文）。
     */
    public Classified classify(String text, RoutingContext routing) {
        // 每条消息独立会话：分类历史不应互相污染——上下文以显式提示注入而非会话记忆
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("intent-classifier")
                .sessionId("cls-" + UUID.randomUUID())
                .build();
        boolean pendingDraft = routing != null && routing.pendingDraft();
        String prompt = text;
        if (pendingDraft) {
            prompt = PENDING_DRAFT_HINT + text;
        } else if (routing != null && routing.lastIntent() != null) {
            prompt = String.format(CONTEXT_HINT_TEMPLATE, routing.lastIntent()) + text;
        }
        try {
            Msg reply = agent.call(prompt, TicketIntent.class, ctx).block(TIMEOUT);
            if (reply != null && reply.hasStructuredData()) {
                TicketIntent ti = reply.getStructuredData(TicketIntent.class);
                if (ti != null && ti.intent() != null) {
                    // 无挂起草稿时 CONFIRM/CANCEL 不可信（prompt 里已约束，双保险归一化）
                    TicketIntent.Intent intent = ti.intent();
                    if (!pendingDraft && (intent == TicketIntent.Intent.CONFIRM_DRAFT
                            || intent == TicketIntent.Intent.CANCEL_DRAFT)) {
                        return fallback("无挂起草稿但输出 " + intent + "，按规则兜底");
                    }
                    return new Classified(intent, ti.reason(), true);
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
