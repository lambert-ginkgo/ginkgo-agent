package com.gingko.dev;

import com.gingko.agent.AgentFactory;
import com.gingko.config.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * M3 会话记忆自动化验收（非交互，PRD M3 验收标准）：
 *
 * <ol>
 *   <li>FR-M3-02 会话隔离：两个用户各自对话，B 的会话看不到 A 说过什么</li>
 *   <li>FR-M3-03 长对话不失控：50 轮超长对话，第 1 轮提到的工单号在第 50 轮仍可被追问引用；
 *       结束时打印上下文消息条数，观察 CompactionConfig 压缩效果</li>
 * </ol>
 *
 * <p>运行：{@code mvn compile exec:java -Dexec.mainClass=com.gingko.dev.LongConversationRun}
 * （与 Main 同一份 Agent 装配，见 {@link AgentFactory}）
 */
public final class LongConversationRun {

    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(2);

    /** 中间轮次灌水话题：刻意避开工单 1024 / VPN 字样，防止污染“第 1 轮记忆”的判定。 */
    private static final String[] FILLERS = {
        "我的工单 1025 什么状态了？",
        "Outlook 收不到邮件一般怎么排查？",
        "我的工单 1026 处理完了吗？",
        "公司新员工怎么申请邮箱账号？",
        "帮我看看工单 1025 现在的进度",
    };

    public static void main(String[] args) {
        AgentConfig config;
        try {
            config = AgentConfig.load();
        } catch (AgentConfig.ConfigException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
            return;
        }
        // E03 验收不启用知识库（保持 M3 场景纯净），E04 检索层验收见 KnowledgeAcceptanceRun
        HarnessAgent agent = AgentFactory.build(config, null);

        isolationCheck(agent);
        longConversationCheck(agent);
    }

    /** 验证一（FR-M3-02）：A 聊过的内容，B 的会话不可见。 */
    private static void isolationCheck(HarnessAgent agent) {
        System.out.println("========== 验证一：会话隔离（FR-M3-02） ==========");
        RuntimeContext ctxA = ctx("verify-isolation-a");
        RuntimeContext ctxB = ctx("verify-isolation-b");

        String a1 = ask(agent, ctxA, "我的工单 1024 现在什么状态？");
        System.out.println("[A 问] 我的工单 1024 现在什么状态？");
        System.out.println("[A 答] " + brief(a1));

        String b1 = ask(agent, ctxB, "我刚才问了什么问题？请复述。");
        System.out.println("[B 问] 我刚才问了什么问题？请复述。");
        System.out.println("[B 答] " + brief(b1));

        System.out.println(">> 判定（B 的回答不应出现 1024 / VPN）："
                + (mentionsAnchor(b1) ? "❌ 串话，隔离失败" : "✅ 隔离正常") + "\n");
    }

    /** 验证二（FR-M3-03）：50 轮长对话，首轮工单号末轮仍可引用。 */
    private static void longConversationCheck(HarnessAgent agent) {
        System.out.println("========== 验证二：50 轮长对话（FR-M3-03） ==========");
        String user = "verify-long";
        RuntimeContext ctx = ctx(user);

        String first = ask(agent, ctx, "我的工单 1024 现在什么状态？");
        System.out.println("[第 1 轮] 问：我的工单 1024 现在什么状态？");
        System.out.println("[第 1 轮] 答：" + brief(first));

        for (int i = 2; i <= 49; i++) {
            String q = FILLERS[(i - 2) % FILLERS.length];
            String ans = ask(agent, ctx, q);
            System.out.println("[第 " + i + " 轮] " + q + " → " + brief(ans));
        }

        String finalQ = "回到我最开始问的那个工单，它现在什么状态？";
        String finalAns = ask(agent, ctx, finalQ);
        System.out.println("[第 50 轮] 问：" + finalQ);
        System.out.println("[第 50 轮] 答：" + finalAns);
        System.out.println(">> 判定（回答应指向工单 1024 / VPN）："
                + (mentionsAnchor(finalAns) ? "✅ 首轮记忆存活" : "❌ 首轮信息丢失"));

        reportContextSize(user, ctx.getSessionId());
    }

    /**
     * 压缩效果观察：直读落盘的 agent_state.json（与 JsonFileAgentStateStore 布局一致）。
     * context 条数远小于实际累积量、summary 非空，都说明 CompactionConfig 生效。
     */
    private static void reportContextSize(String userId, String sessionId) {
        Path stateFile = Path.of(System.getProperty("user.home"), ".agentscope", "state",
                AgentFactory.AGENT_NAME, userId, sessionId, "agent_state.json");
        try {
            JsonNode root = new ObjectMapper().readTree(stateFile.toFile());
            int contextSize = root.path("context").size();
            String summary = root.path("summary").asText("");
            System.out.println(">> 压缩观察：状态文件 context 消息条数 = " + contextSize
                    + "（未压缩应为 100 条以上；明显小于该值说明压缩生效）");
            System.out.println(">> 压缩观察：summary "
                    + (summary.isBlank()
                            ? "为空（本会话未发生过压缩）"
                            : "非空，长度 " + summary.length() + " 字符（发生过压缩，历史前缀已蒸馏为摘要）"));
        } catch (Exception e) {
            System.out.println(">> 压缩观察：读取状态文件失败 - " + e.getMessage());
        }
    }

    private static String ask(HarnessAgent agent, RuntimeContext ctx, String question) {
        Msg reply = agent.call(new UserMessage(question), ctx).block(TURN_TIMEOUT);
        return reply == null ? "" : safeText(reply);
    }

    private static String safeText(Msg msg) {
        String text = msg.getTextContent();
        return text == null ? "" : text;
    }

    private static boolean mentionsAnchor(String answer) {
        return answer != null && (answer.contains("1024") || answer.contains("VPN"));
    }

    private static String brief(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 90 ? flat : flat.substring(0, 90) + "…";
    }

    private static RuntimeContext ctx(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId("verify-" + UUID.randomUUID())
                .build();
    }
}
