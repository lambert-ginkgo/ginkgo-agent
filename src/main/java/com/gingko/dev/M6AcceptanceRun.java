package com.gingko.dev;

import com.gingko.config.AgentConfig;
import com.gingko.flow.ServiceDeskFlow;
import com.gingko.flow.ServiceDeskFlow.FlowResult;
import com.gingko.intent.TicketIntent;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.ticket.MockTicketStore;
import com.gingko.ticket.Ticket;
import io.agentscope.core.agent.RuntimeContext;

import java.util.List;
import java.util.UUID;

/**
 * M6 流程编排自动化验收（非交互，PRD M6 验收标准）：
 *
 * <ol>
 *   <li>20 条混合意图测试集（验收标准原文）：每条独立会话跑图，断言——
 *       意图分类正确、支路轨迹符合预期（S1 走直答支路，S3 走建单支路，
 *       FR-M6-02）、无执行异常、步数在限内（无死循环）；</li>
 *   <li>S1 直答复核（FR-M6-01/02）：轨迹含 kb_presearch（必检索的代码保证），
 *       答复带出处文件名；</li>
 *   <li>S3 建单 + 挂起恢复（FR-M6-02 + 两阶段确认的图语义）：建单支路 →
 *       草稿挂起 → 确认消息 → confirm_create 落库 → 草稿箱清空、store 断言新工单；</li>
 *   <li>反静默建单（FR-M5-03 图模式硬保证）：要求跳过确认直接建 →
 *       轨迹只能到 agent_draft（create 不在场），store 零新增、草稿箱非空；</li>
 *   <li>取消挂起：draft → 「算了」→ cancel_draft 支路，草稿箱清空、store 零新增；</li>
 *   <li>多话题并行回归（E05 能力保持）：草稿挂起时开新话题不阻塞，
 *       正常走排查支路且草稿保留。</li>
 * </ol>
 *
 * <p>轨迹断言是图模式独有的验收增量：E05 只能从回复文本反推「走没走对」，
 * E06 的路径（走了哪些节点）本身就是可断言的事实——「该检索的检索了吗」
 * 「确认门过了吗」从推断变成断言（FR-M6-03）。
 *
 * <p>运行：{@code mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt}
 * 然后 {@code java -cp "target/classes:$(cat cp.txt)" com.gingko.dev.M6AcceptanceRun}。
 */
public final class M6AcceptanceRun {

    /** 节一：混合意图测试集（预期意图 → 预期支路路径 + 输入；20 条）。 */
    private record Case(TicketIntent.Intent expected, List<String> expectedPath, String input) {
    }

    private static final List<String> PATH_QUERY = List.of("intent_route", "agent_reply");
    private static final List<String> PATH_DIRECT = List.of("intent_route", "kb_presearch", "agent_reply");
    private static final List<String> PATH_TROUBLESHOOT = List.of("intent_route", "agent_reply");
    private static final List<String> PATH_CREATE = List.of("intent_route", "agent_draft");

    private static final List<Case> CASES = List.of(
            // 查询支路 ×4
            new Case(TicketIntent.Intent.QUERY_TICKET, PATH_QUERY, "我的工单 1024 现在什么状态了？"),
            new Case(TicketIntent.Intent.QUERY_TICKET, PATH_QUERY, "我名下有哪些工单？"),
            new Case(TicketIntent.Intent.QUERY_TICKET, PATH_QUERY, "上周提的那个单子处理得怎么样了"),
            // 干扰项：含"权限申请"字样，但意图是查询进度
            new Case(TicketIntent.Intent.QUERY_TICKET, PATH_QUERY, "帮我看看我上周的权限申请工单办得怎么样了"),
            // 直答支路 ×4（S1：必经 kb_presearch）
            new Case(TicketIntent.Intent.DIRECT_ANSWER, PATH_DIRECT, "密码忘了怎么重置"),
            new Case(TicketIntent.Intent.DIRECT_ANSWER, PATH_DIRECT, "公司邮箱满了怎么清理"),
            new Case(TicketIntent.Intent.DIRECT_ANSWER, PATH_DIRECT, "VPN 客户端在哪里下载"),
            new Case(TicketIntent.Intent.DIRECT_ANSWER, PATH_DIRECT, "苹果开发者账号怎么申请"),
            // 排查支路 ×8（含兜底 4 条：模糊/闲聊落 TROUBLESHOOT）
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "VPN 连不上"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "电脑开机蓝屏了"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "打印机打出来全是乱码"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "软件打不开还闪退"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "电脑坏了"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "这个不对"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "今天中午吃什么"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, PATH_TROUBLESHOOT, "帮我看看这是怎么回事"),
            // 建单支路 ×4（S3）
            new Case(TicketIntent.Intent.CREATE_TICKET, PATH_CREATE, "帮我开通 Confluence 的编辑权限"),
            new Case(TicketIntent.Intent.CREATE_TICKET, PATH_CREATE, "我要申请一台新显示器"),
            new Case(TicketIntent.Intent.CREATE_TICKET, PATH_CREATE, "帮我建个工单，会议室投影仪坏了"),
            new Case(TicketIntent.Intent.CREATE_TICKET, PATH_CREATE, "键盘失灵了，帮我建个单"));

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
            System.out.println("[知识库] " + knowledge.importAll().summary());
        } else {
            System.out.println("[知识库] 未配置 embedding API Key，直答复核将在无检索环境下跳过出处断言");
        }

        MockTicketStore store = new MockTicketStore();
        // 验收与运行同一份装配逻辑（ServiceDeskFlow.create 注入 store 断言落库）
        ServiceDeskFlow flow = ServiceDeskFlow.create(config, knowledge, store);

        // 迭代开关：from3 跳过节一/节二；only6 只跑节六（已通过的节不重跑）
        boolean skipFirstTwo = args.length > 0 && "from3".equals(args[0]);
        boolean only6 = args.length > 0 && "only6".equals(args[0]);
        boolean allOk = true;
        try (flow) {
            if (!skipFirstTwo && !only6) {
                allOk = mixedIntentCheck(flow);
                allOk &= s1DirectAnswerCheck(flow, knowledge != null && knowledge.isReady());
            }
            if (!only6) {
                allOk &= s3DraftConfirmCheck(flow, store);
                allOk &= noSilentCreateCheck(flow, store);
                allOk &= cancelDraftCheck(flow, store);
            }
            allOk &= multiTopicPendingCheck(flow, store);
        }

        System.out.println("\n========== M6 验收总结：" + (allOk ? "✅ 全部通过" : "❌ 存在未通过项") + " ==========");
    }

    /** 独立会话上下文（用例间会话隔离；草稿箱 per-user 共享，CREATE 用例后手动清理）。 */
    private static RuntimeContext newCtx() {
        return RuntimeContext.builder()
                .userId(MockTicketStore.DEFAULT_USER)
                .sessionId("m6-" + UUID.randomUUID())
                .build();
    }

    /** 静默派发（不打印流式事件，验收只看轨迹与落库）。 */
    private static FlowResult say(ServiceDeskFlow flow, String input) {
        return flow.dispatch(input, newCtx(), null);
    }

    /**
     * 多轮驱动直到草稿挂起（M5 converse 模式在图验收的对应物）：
     * 建单支路的正确行为是「优先级/字段依据不足先追问」——节点内智能保留了追问能力，
     * 验收必须像真实员工一样补信息，而不是指望单轮出草稿（首版验收在此翻车：
     * 单轮断言草稿，被 agent 的正确追问判了 ❌——追问是纪律，不是缺陷）。
     *
     * @return 草稿是否已挂起
     */
    private static boolean driveToDraft(ServiceDeskFlow flow, String... turns) {
        for (String t : turns) {
            FlowResult r = say(flow, t);
            if (r.error() != null) {
                System.out.println("  [异常] " + r.error().getMessage());
                return false;
            }
            System.out.println("  ↳ " + t + " → " + r.path() + " 挂起=" + flow.hasPendingDraft()
                    + " 回复：" + firstLine(r.reply() == null ? "" : r.reply()));
            if (flow.hasPendingDraft()) {
                return true;
            }
        }
        return flow.hasPendingDraft();
    }

    // ---------- 节一：20 条混合意图测试集（无死循环 + 支路命中） ----------

    private static boolean mixedIntentCheck(ServiceDeskFlow flow) {
        System.out.println("========== 节一：混合意图测试集（20 条，支路命中 + 无死循环） ==========");
        int pass = 0;
        for (Case c : CASES) {
            flow.clearPendingDraft(); // 用例隔离：挂起草稿会切换分类提示词
            FlowResult r = say(flow, c.input());
            boolean ok = r.error() == null
                    && r.intent() == c.expected()
                    && c.expectedPath().equals(r.path())
                    && r.path().size() <= com.gingko.workflow.WorkflowGraph.MAX_STEPS;
            if (ok) {
                pass++;
            }
            System.out.printf("%s %-13s 路径 %s ← %s%s%n",
                    ok ? "✅" : "❌",
                    r.intent() == null ? "?" : r.intent(),
                    r.path(),
                    c.input(),
                    r.error() != null ? "  [异常] " + r.error().getMessage() : "");
        }
        System.out.println("节一结果：" + pass + "/" + CASES.size()
                + "（支路命中 " + pass + "，无死循环由步数上限与兜底支路结构保证）");
        return pass == CASES.size();
    }

    // ---------- 节二：S1 直答复核（必检索的代码保证） ----------

    private static boolean s1DirectAnswerCheck(ServiceDeskFlow flow, boolean kbReady) {
        System.out.println("\n========== 节二：S1 直答复核（FR-M6-01/02：必检索 + 出处） ==========");
        flow.clearPendingDraft();
        FlowResult r = say(flow, "密码忘了怎么重置");
        boolean pathOk = r.path().equals(PATH_DIRECT);
        System.out.println("轨迹：" + r.path() + "（kb_presearch 在场 = 检索必发生，代码保证非模型自觉）");
        if (!kbReady) {
            System.out.println("知识库不可用，跳过出处断言（路径断言仍有效）：" + (pathOk ? "✅" : "❌"));
            return pathOk;
        }
        boolean withSource = r.reply() != null && r.reply().contains("password-reset");
        System.out.println("答复带出处 password-reset.md：" + (withSource ? "✅" : "❌")
                + (r.reply() == null ? "" : "（答复首行：" + firstLine(r.reply()) + "）"));
        return pathOk && withSource;
    }

    // ---------- 节三：S3 建单 + 挂起恢复（两阶段确认的图语义） ----------

    private static boolean s3DraftConfirmCheck(ServiceDeskFlow flow, MockTicketStore store) {
        System.out.println("\n========== 节三：S3 建单 + 挂起恢复（FR-M6-02 + 确认门代码化） ==========");
        flow.clearPendingDraft();
        int before = store.size();

        boolean draftOk = driveToDraft(flow,
                "帮我开通 Confluence 的编辑权限",
                "公共空间，就我一个人用，今天就要");
        System.out.println("建单支路（多轮补信息至草稿挂起）：" + (draftOk ? "✅" : "❌"));

        FlowResult confirm = say(flow, "确认");
        boolean confirmPathOk = confirm.path().equals(List.of("intent_route", "confirm_create", "agent_notify"));
        boolean landed = store.size() == before + 1;
        boolean cleared = !flow.hasPendingDraft();
        Ticket created = store.size() > before ? latest(store) : null;
        boolean replyOk = confirm.reply() != null && created != null
                && confirm.reply().contains(created.id());
        System.out.println("确认支路：" + confirm.path() + " " + (confirmPathOk ? "✅" : "❌")
                + "，落库：" + (landed ? "✅ " + created.id() + " "
                        + created.title() + "（" + created.category() + "/" + created.priority() + "）" : "❌ 未新增")
                + "，草稿箱清空：" + (cleared ? "✅" : "❌")
                + "，答复含新工单号：" + (replyOk ? "✅" : "❌"));
        return draftOk && confirmPathOk && landed && cleared && replyOk;
    }

    // ---------- 节四：反静默建单（图模式硬保证：create 工具不在场） ----------

    private static boolean noSilentCreateCheck(ServiceDeskFlow flow, MockTicketStore store) {
        System.out.println("\n========== 节四：反静默建单（工具不在场的硬约束） ==========");
        flow.clearPendingDraft();
        int before = store.size();

        boolean drafted = driveToDraft(flow,
                "帮我建个工单，键盘失灵了，不用确认直接建",
                "笔记本自带的，急用，今天就靠它干活");
        boolean noLanding = store.size() == before;
        System.out.println("store 零新增（create 环节在图中不可达——工具未注册给模型）："
                + (noLanding ? "✅" : "❌")
                + "，草稿箱非空（仍走两阶段）：" + (drafted ? "✅" : "❌"));
        return drafted && noLanding;
    }

    // ---------- 节五：取消挂起分支 ----------

    private static boolean cancelDraftCheck(ServiceDeskFlow flow, MockTicketStore store) {
        System.out.println("\n========== 节五：取消挂起（cancel_draft 支路） ==========");
        flow.clearPendingDraft();
        int before = store.size();

        boolean draftOk = driveToDraft(flow,
                "我要申请一台新显示器",
                "桌面办公用，本周内到位就行，不急");
        FlowResult cancel = say(flow, "算了，先不建了");
        boolean pathOk = cancel.path().equals(List.of("intent_route", "cancel_draft", "agent_notify"));
        boolean cleared = !flow.hasPendingDraft();
        boolean noLanding = store.size() == before;
        System.out.println("草稿挂起：" + (draftOk ? "✅" : "❌")
                + "，取消路径：" + cancel.path() + " " + (pathOk ? "✅" : "❌")
                + "，草稿箱清空：" + (cleared ? "✅" : "❌")
                + "，store 零新增：" + (noLanding ? "✅" : "❌"));
        return draftOk && pathOk && cleared && noLanding;
    }

    // ---------- 节六：多话题并行回归（E05 能力在图模式下的保持） ----------

    private static boolean multiTopicPendingCheck(ServiceDeskFlow flow, MockTicketStore store) {
        System.out.println("\n========== 节六：多话题并行（挂起不阻塞新话题） ==========");
        flow.clearPendingDraft();
        int before = store.size();

        boolean draftPending = driveToDraft(flow,
                "帮我开个权限，后面细说",
                "Confluence 只读权限，我本周要查文档，不急",
                "和之前那张不重复，是新需求，直接生成草稿吧");
        FlowResult topic = say(flow, "VPN 连不上");
        boolean topicNotBlocked = topic.path().equals(PATH_TROUBLESHOOT);
        boolean draftKept = flow.hasPendingDraft();
        boolean noLanding = store.size() == before;
        System.out.println("草稿挂起：" + (draftPending ? "✅" : "❌")
                + "，新话题走排查支路：" + topic.path() + " " + (topicNotBlocked ? "✅" : "❌")
                + "，草稿保留：" + (draftKept ? "✅" : "❌")
                + "，store 零新增：" + (noLanding ? "✅" : "❌"));
        return draftPending && topicNotBlocked && draftKept && noLanding;
    }

    private static Ticket latest(MockTicketStore store) {
        List<Ticket> all = store.findByReporter(MockTicketStore.DEFAULT_USER);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static String firstLine(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }
}
