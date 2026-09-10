package com.gingko;

import com.gingko.agent.AgentFactory;
import com.gingko.config.AgentConfig;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.knowledge.KnowledgeService.ImportStats;
import com.gingko.session.SessionHistory;
import com.gingko.session.SessionHistory.SessionInfo;
import com.gingko.ticket.MockTicketStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

/**
 * M1 对话基座（E01）+ M2 工单查询工具（E02）+ M3 会话记忆（E03）+ M4 知识库问答（E04）。
 *
 * <p>E03 新增命令：
 * <ul>
 *   <li>{@code /user <name>}：切换对话用户（FR-M3-02 会话隔离——各用户独立会话状态与记忆）</li>
 *   <li>{@code /sessions}：列出当前用户的历史会话（状态落盘于 ~/.agentscope/state）</li>
 *   <li>{@code /resume <序号>}：恢复指定历史会话，继续上次进度</li>
 * </ul>
 *
 * <p>E04 新增命令：
 * <ul>
 *   <li>{@code /kb}：查看知识库统计（文档数/片段数）</li>
 *   <li>{@code /kb reload}：重新扫描导入知识库目录（FR-M4-02：更新文档后立即生效）</li>
 * </ul>
 * 原有命令：/reset 重置会话（FR-M1-04）；/quit 退出。Agent 装配见 {@link AgentFactory}。
 * 未配置 embedding API Key 时知识库问答功能降级关闭，其余功能不受影响。
 */
public class Main {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public static void main(String[] args) {
        AgentConfig config;
        try {
            config = AgentConfig.load();
        } catch (AgentConfig.ConfigException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
            return;
        }

        KnowledgeService knowledge = null;
        if (config.embeddingConfigured()) {
            knowledge = KnowledgeService.create(config);
            ImportStats stats = knowledge.importAll();
            System.out.println("[知识库] " + stats.summary());
        } else {
            System.out.println("[知识库] 未配置 embedding API Key，知识库问答不可用（其余功能不受影响）。");
            System.out.println("[知识库] 配置方法：设置环境变量 EMBEDDING_API_KEY，"
                    + "或 config/application.properties 的 ginkgo.embedding.api-key（详见 application.properties.example）。");
        }

        HarnessAgent agent = AgentFactory.build(config, knowledge);

        String currentUser = MockTicketStore.DEFAULT_USER;
        Map<String, RuntimeContext> contexts = new HashMap<>();
        contexts.put(currentUser, newContext(currentUser));

        System.out.println("ginkgo-agent 已启动（模型 " + config.modelName() + "）。当前用户：" + currentUser);
        System.out.println("命令：/reset 重置会话 | /user <name> 切换用户 | /sessions 历史会话 | "
                + "/resume <序号> 恢复会话 | /kb 知识库 | /help 帮助 | /quit 退出");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n你(" + currentUser + ") > ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            if (input.equals("/quit") || input.equals("/exit")) {
                System.out.println("再见。");
                return;
            }
            if (input.equals("/help")) {
                printHelp();
                continue;
            }
            if (input.equals("/reset")) {
                contexts.put(currentUser, newContext(currentUser));
                System.out.println("[会话已重置，上下文已清空]");
                continue;
            }
            if (input.startsWith("/user")) {
                String name = input.replaceFirst("^/user\\s+", "").trim();
                if (name.isEmpty()) {
                    System.out.println("用法：/user <name>（如 /user zhangsan）");
                    continue;
                }
                currentUser = name;
                RuntimeContext ctx = contexts.computeIfAbsent(name, Main::newContext);
                String note = name.equals(MockTicketStore.DEFAULT_USER)
                        ? ""
                        : "\n注：CLI 期工单数据仍以 " + MockTicketStore.DEFAULT_USER
                                + " 身份查询（数据级权限 E07 接入），本命令只切换对话记忆";
                System.out.println("[已切换到 " + name + "，会话 " + shortId(ctx.getSessionId()) + "]" + note);
                continue;
            }
            if (input.equals("/sessions")) {
                printSessions(currentUser, contexts.get(currentUser));
                continue;
            }
            if (input.startsWith("/resume")) {
                resumeSession(input, currentUser, contexts);
                continue;
            }
            if (input.startsWith("/kb")) {
                handleKb(input, knowledge);
                continue;
            }

            System.out.print("agent > ");
            try {
                agent.streamEvents(new UserMessage(input), contexts.get(currentUser))
                        .doOnNext(Main::printEvent)
                        .blockLast();
                System.out.println();
            } catch (Exception e) {
                System.err.println("\n[调用失败] " + e.getMessage() + "（可重试，或 /reset 开新会话）");
            }
        }
    }

    private static void printSessions(String userId, RuntimeContext current) {
        List<SessionInfo> sessions = SessionHistory.list(AgentFactory.AGENT_NAME, userId);
        if (sessions.isEmpty()) {
            System.out.println("暂无历史会话。");
            return;
        }
        System.out.println("历史会话（最新在前）：");
        for (int i = 0; i < sessions.size(); i++) {
            SessionInfo s = sessions.get(i);
            String mark = s.sessionId().equals(current.getSessionId()) ? "  ← 当前" : "";
            System.out.printf("%2d. %s  （%s）%s%n", i + 1, s.sessionId(), TIME_FMT.format(s.lastModified()), mark);
        }
        System.out.println("用 /resume <序号> 恢复对应会话。");
    }

    private static void resumeSession(String input, String userId, Map<String, RuntimeContext> contexts) {
        String arg = input.replaceFirst("^/resume\\s+", "").trim();
        if (arg.startsWith("<") && arg.endsWith(">")) {
            arg = arg.substring(1, arg.length() - 1);
        }
        if (arg.isEmpty()) {
            System.out.println("用法：/resume <序号>（序号见 /sessions，也可直接粘贴完整会话 ID）");
            return;
        }
        List<SessionInfo> sessions = SessionHistory.list(AgentFactory.AGENT_NAME, userId);
        String sessionId = resolveSessionId(arg, sessions);
        if (sessionId == null) {
            System.out.println("找不到会话 " + arg
                    + "——支持 /resume <序号> 或 /resume <完整会话ID>，先用 /sessions 查看。");
            return;
        }
        contexts.put(userId, RuntimeContext.builder().userId(userId).sessionId(sessionId).build());
        System.out.println("[已挂载历史会话 " + sessionId + "，继续对话即可接上上次进度]");
    }

    /** /kb 命令：查看知识库统计；/kb reload 重新扫描导入（FR-M4-02）。 */
    private static void handleKb(String input, KnowledgeService knowledge) {
        if (knowledge == null) {
            System.out.println("知识库未启用：未配置 embedding API Key（EMBEDDING_API_KEY 或 ginkgo.embedding.api-key）。");
            return;
        }
        String arg = input.replaceFirst("^/kb\\s*", "").trim();
        if (arg.equals("reload")) {
            boolean wasReady = knowledge.isReady();
            ImportStats stats = knowledge.reload();
            System.out.println("[知识库] 已重新导入：" + stats.summary());
            System.out.println("[知识库] 文档目录：" + KnowledgeService.KNOWLEDGE_DIR.toAbsolutePath());
            if (stats.success() && !wasReady) {
                System.out.println("[知识库] 注意：本次进程启动时导入失败、检索工具未注册，重启进程后知识库问答才会启用。");
            }
            return;
        }
        if (!arg.isEmpty()) {
            System.out.println("用法：/kb 查看统计 | /kb reload 重新扫描导入");
            return;
        }
        ImportStats stats = knowledge.stats();
        System.out.println("[知识库] " + stats.summary());
        System.out.println("[知识库] 文档目录：" + KnowledgeService.KNOWLEDGE_DIR.toAbsolutePath()
                + "（修改/新增 .md 后执行 /kb reload 生效）");
    }

    /** /resume 参数解析：数字按序号（最新在前），否则按完整会话 ID 精确匹配。 */
    private static String resolveSessionId(String arg, List<SessionInfo> sessions) {
        if (sessions.isEmpty()) {
            return null;
        }
        try {
            int n = Integer.parseInt(arg);
            return (n >= 1 && n <= sessions.size()) ? sessions.get(n - 1).sessionId() : null;
        } catch (NumberFormatException e) {
            return sessions.stream()
                    .map(SessionInfo::sessionId)
                    .filter(id -> id.equals(arg))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void printHelp() {
        System.out.println("""
                /reset        重置当前用户的会话（清空上下文）
                /user <name>  切换对话用户（各用户记忆独立）
                /sessions     列出当前用户的历史会话
                /resume <n>   恢复第 n 个历史会话（或直接粘贴完整会话 ID）
                /kb           查看知识库统计（/kb reload 重新导入）
                /quit         退出""");
    }

    private static void printEvent(AgentEvent event) {
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> System.out.print(((TextBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> System.out.print("\n[调用工具 " + ((ToolCallStartEvent) event).getToolCallName() + "] ");
            default -> {
            }
        }
    }

    private static RuntimeContext newContext(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId("cli-" + UUID.randomUUID())
                .build();
    }

    private static String shortId(String sessionId) {
        return sessionId.length() <= 12 ? sessionId : sessionId.substring(0, 12) + "…";
    }
}
