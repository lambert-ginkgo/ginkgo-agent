package com.gingko;

import com.gingko.agent.AgentFactory;
import com.gingko.config.AgentConfig;
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
 * M1 对话基座（E01）+ M2 工单查询工具（E02）+ M3 会话记忆（E03）。
 *
 * <p>E03 新增命令：
 * <ul>
 *   <li>{@code /user <name>}：切换对话用户（FR-M3-02 会话隔离——各用户独立会话状态与记忆）</li>
 *   <li>{@code /sessions}：列出当前用户的历史会话（状态落盘于 ~/.agentscope/state）</li>
 *   <li>{@code /resume <序号>}：恢复指定历史会话，继续上次进度</li>
 * </ul>
 * 原有命令：/reset 重置会话（FR-M1-04）；/quit 退出。Agent 装配见 {@link AgentFactory}。
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

        HarnessAgent agent = AgentFactory.build(config);

        String currentUser = MockTicketStore.DEFAULT_USER;
        Map<String, RuntimeContext> contexts = new HashMap<>();
        contexts.put(currentUser, newContext(currentUser));

        System.out.println("ginkgo-agent 已启动（模型 " + config.modelName() + "）。当前用户：" + currentUser);
        System.out.println("命令：/reset 重置会话 | /user <name> 切换用户 | /sessions 历史会话 | "
                + "/resume <序号> 恢复会话 | /help 帮助 | /quit 退出");

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
        int n;
        try {
            n = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.out.println("用法：/resume <序号>（序号见 /sessions）");
            return;
        }
        List<SessionInfo> sessions = SessionHistory.list(AgentFactory.AGENT_NAME, userId);
        if (n < 1 || n > sessions.size()) {
            System.out.println("序号超出范围（1-" + sessions.size() + "），先用 /sessions 查看。");
            return;
        }
        String sessionId = sessions.get(n - 1).sessionId();
        contexts.put(userId, RuntimeContext.builder().userId(userId).sessionId(sessionId).build());
        System.out.println("[已挂载历史会话 " + sessionId + "，继续对话即可接上上次进度]");
    }

    private static void printHelp() {
        System.out.println("""
                /reset        重置当前用户的会话（清空上下文）
                /user <name>  切换对话用户（各用户记忆独立）
                /sessions     列出当前用户的历史会话
                /resume <n>   恢复第 n 个历史会话
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
