package com.gingko;

import com.gingko.config.AgentConfig;
import com.gingko.ticket.MockTicketStore;
import com.gingko.ticket.TicketTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Path;
import java.util.Scanner;
import java.util.UUID;

/**
 * M1 对话基座（E01）+ M2 工单查询工具（E02）：Agent 能调用 mock 工单数据源回答工单问题。
 * 命令：/reset 重置会话（清空上下文重新开始，FR-M1-04）；/quit 退出。
 */
public class Main {

    private static final String USER_ID = "cli-user";

    public static void main(String[] args) {
        AgentConfig config;
        try {
            config = AgentConfig.load();
        } catch (AgentConfig.ConfigException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
            return;
        }

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TicketTools(new MockTicketStore(), MockTicketStore.DEFAULT_USER));

        HarnessAgent agent = HarnessAgent.builder()
                .name("ginkgo-service-desk")
                .sysPrompt("你是企业 IT 服务台智能体 ginkgo。用简洁的中文回答员工的 IT 求助；"
                        + "涉及工单状态、工单列表的问题，先调用工具查询再回答，不要编造工单信息；不知道就说不知道。")
                .model(model)
                .toolkit(toolkit)
                .workspace(Path.of(".agentscope", "workspace"))
                .build();

        RuntimeContext ctx = newContext();
        System.out.println("ginkgo-agent 已启动（模型 " + config.modelName() + "）。输入问题开始对话，/reset 重置会话，/quit 退出。");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n你 > ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            switch (input) {
                case "/quit", "/exit" -> {
                    System.out.println("再见。");
                    return;
                }
                case "/reset" -> {
                    ctx = newContext();
                    System.out.println("[会话已重置，上下文已清空]");
                    continue;
                }
                default -> {
                }
            }

            System.out.print("agent > ");
            try {
                agent.streamEvents(new UserMessage(input), ctx)
                        .doOnNext(Main::printEvent)
                        .blockLast();
                System.out.println();
            } catch (Exception e) {
                System.err.println("\n[调用失败] " + e.getMessage() + "（可重试，或 /reset 开新会话）");
            }
        }
    }

    private static void printEvent(AgentEvent event) {
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> System.out.print(((TextBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> System.out.print("\n[调用工具 " + ((ToolCallStartEvent) event).getToolCallName() + "] ");
            default -> {
            }
        }
    }

    private static RuntimeContext newContext() {
        return RuntimeContext.builder()
                .userId(USER_ID)
                .sessionId("cli-" + UUID.randomUUID())
                .build();
    }
}
