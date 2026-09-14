package com.gingko.dev;

import com.gingko.agent.AgentFactory;
import com.gingko.config.AgentConfig;
import com.gingko.intent.IntentClassifier;
import com.gingko.intent.TicketIntent;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.ticket.MockTicketStore;
import com.gingko.ticket.Ticket;
import com.gingko.ticket.TicketDraft;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * M5 智能建单自动化验收（非交互，PRD M5 验收标准 + S2/S3 场景）：
 *
 * <ol>
 *   <li>意图分类准确率（FR-M5-01）：独立分类器（裸 ReActAgent + 结构化输出
 *       call(text, Class, ctx)）跑 16 条混合意图测试集，统计准确率与兜底生效；
 *       顺带实测 record + 嵌套 enum 的结构化输出支持（官方 v1 文档只承诺
 *       “public 字段 POJO + 无参构造”，v2 文档无此页）</li>
 *   <li>S3 确认建单主链路（FR-M5-02/03）：权限申请 → 草稿卡 → 确认 → 落库 → 工单号回查</li>
 *   <li>模糊描述追问（M5 验收标准）：“电脑坏了”应追问而非瞎猜优先级直接建单</li>
 *   <li>字段修改（M5 验收标准）：草稿 → 改优先级 → 重新草稿 → 确认落库，落库的是修改后字段</li>
 *   <li>反静默建单（FR-M5-03）：员工要求跳过确认直接建 → 仍须展示草稿卡，确认后才落库</li>
 *   <li>S2 排查转建单（FR-M5-04）：VPN 排查未解决 → 建单携带已排查上下文</li>
 * </ol>
 *
 * <p>运行（pom 的 exec.mainClass 固定为 Main，-Dexec.mainClass 不生效）：
 * {@code mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt}
 * 然后 {@code java -cp target/classes:cp.txt com.gingko.dev.M5AcceptanceRun}。
 */
public final class M5AcceptanceRun {

    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_TURNS = 8;

    /** 节一：意图分类测试集（预期意图，输入；含干扰项与兜底项）。 */
    private record Case(TicketIntent.Intent expected, String input) {
    }

    private static final List<Case> INTENT_CASES = List.of(
            new Case(TicketIntent.Intent.QUERY_TICKET, "我的工单 1024 现在什么状态了？"),
            new Case(TicketIntent.Intent.QUERY_TICKET, "我名下有哪些工单？"),
            new Case(TicketIntent.Intent.QUERY_TICKET, "上周提的那个单子处理得怎么样了"),
            // 干扰项：含“权限申请”字样，但意图是查询进度
            new Case(TicketIntent.Intent.QUERY_TICKET, "帮我看看我上周的权限申请工单办得怎么样了"),
            new Case(TicketIntent.Intent.DIRECT_ANSWER, "密码忘了怎么重置"),
            new Case(TicketIntent.Intent.DIRECT_ANSWER, "公司邮箱满了怎么清理"),
            new Case(TicketIntent.Intent.DIRECT_ANSWER, "VPN 客户端在哪里下载"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, "VPN 连不上"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, "电脑开机蓝屏了"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, "打印机打出来全是乱码"),
            new Case(TicketIntent.Intent.CREATE_TICKET, "帮我开通 Confluence 的编辑权限"),
            new Case(TicketIntent.Intent.CREATE_TICKET, "我要申请一台新显示器"),
            new Case(TicketIntent.Intent.CREATE_TICKET, "帮我建个工单，会议室投影仪坏了"),
            // 兜底项：模糊描述与无关闲聊，分类规则要求落 TROUBLESHOOT
            new Case(TicketIntent.Intent.TROUBLESHOOT, "电脑坏了"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, "这个不对"),
            new Case(TicketIntent.Intent.TROUBLESHOOT, "今天中午吃什么"));

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
            System.out.println("[知识库] 未配置 embedding API Key，节六排查转建单在无检索环境下进行");
        }

        // 与 Main 同一份装配（AgentFactory），验收环境 = 运行环境；store 注入以便断言落库
        MockTicketStore store = new MockTicketStore();
        HarnessAgent agent = AgentFactory.build(config, knowledge, store);

        int pass = intentClassificationCheck(config);
        boolean allOk = pass == INTENT_CASES.size();

        allOk &= s3ConfirmCreateCheck(agent, store);
        allOk &= vagueDescriptionCheck(agent, store);
        allOk &= fieldModifyCheck(agent, store);
        allOk &= noSilentCreateCheck(agent, store);
        allOk &= s2TroubleshootCreateCheck(agent, store);

        System.out.println("\n========== M5 验收总结："
                + (allOk ? "✅ 全部通过" : "❌ 存在未通过项（节一准确率 " + pass + "/" + INTENT_CASES.size() + "）")
                + " ==========");
    }

    /** 节一：意图分类测试集（FR-M5-01）。返回通过条数。 */
    private static int intentClassificationCheck(AgentConfig config) {
        System.out.println("========== 节一：意图分类准确率（FR-M5-01，16 条测试集） ==========");
        int pass = 0;
        int fallbackCount = 0;
        try (IntentClassifier classifier = IntentClassifier.create(config)) {
            for (Case c : INTENT_CASES) {
                IntentClassifier.Classified r = classifier.classify(c.input());
                boolean ok = r.intent() == c.expected();
                if (!r.fromModel()) {
                    fallbackCount++;
                }
                if (ok) {
                    pass++;
                }
                System.out.printf("%s %-13s ← %s  理由：%s%s%n",
                        ok ? "✅" : "❌",
                        r.intent(),
                        brief(c.input()),
                        brief(r.reason()),
                        r.fromModel() ? "" : "（兜底生效）");
            }
        }
        System.out.println(">> 判定：准确率 " + pass + "/" + INTENT_CASES.size()
                + "，兜底触发 " + fallbackCount + " 条（模型输出不可解析时落 TROUBLESHOOT）");
        return pass;
    }

    /** 节二：S3 场景——权限申请 → 草稿卡 → 确认 → 落库 → 回查（FR-M5-02/03）。 */
    private static boolean s3ConfirmCreateCheck(HarnessAgent agent, MockTicketStore store) {
        System.out.println("\n========== 节二：S3 确认建单主链路（FR-M5-02/03） ==========");
        RuntimeContext ctx = ctx("m5-s3");
        int before = store.size();

        Outcome r = converse(agent, ctx, "帮我开通 Confluence 的编辑权限",
                List.of("用于项目文档协作", "就我一个人用，日常文档要写"), null);
        printReplies(r);

        boolean created = store.size() == before + 1;
        System.out.println(">> 判定一（草稿确认后落库）：" + (created ? "✅ 新增 1 单" : "❌ 未落库"));
        if (!created) {
            return false;
        }
        Ticket t = latest(store);
        boolean fieldsOk = "权限".equals(t.category())
                && TicketDraft.PRIORITIES.contains(t.priority())
                && t.title() != null && t.title().contains("Confluence")
                && Ticket.STATUS_NEW.equals(t.status());
        System.out.println(">> 判定二（字段抽取）："
                + (fieldsOk ? "✅ 分类/优先级/标题/状态 = " + t.category() + "/" + t.priority()
                        + "/" + t.title() + "/" + t.status()
                        : "❌ " + t.category() + "/" + t.priority() + "/" + t.title() + "/" + t.status()));

        // 落库后按工单号回查（M5 验收标准：建单成功返回工单号）
        String q = "工单 " + t.id() + " 什么状态";
        String reply = ask(agent, ctx, q);
        System.out.println("[回查] " + q + " → " + brief(reply));
        boolean queryOk = reply.contains(t.id());
        System.out.println(">> 判定三（工单号回查）：" + (queryOk ? "✅" : "❌"));
        return fieldsOk && queryOk;
    }

    /** 节三：模糊描述（M5 验收标准）——“电脑坏了”应追问，不瞎猜优先级直接建单。 */
    private static boolean vagueDescriptionCheck(HarnessAgent agent, MockTicketStore store) {
        System.out.println("\n========== 节三：模糊描述追问（M5 验收标准） ==========");
        RuntimeContext ctx = ctx("m5-vague");
        int before = store.size();

        String reply = ask(agent, ctx, "电脑坏了");
        System.out.println("[回复] " + brief(reply));

        boolean askedBack = reply.contains("？") || reply.contains("?");
        boolean notCreated = store.size() == before;
        boolean ok = askedBack && notCreated;
        System.out.println(">> 判定（追问且未落库）："
                + (ok ? "✅" : "❌ 追问=" + askedBack + "，未落库=" + notCreated));
        return ok;
    }

    /** 节四：字段修改（M5 验收标准）——草稿 → 改优先级 → 重新草稿 → 确认，落库为修改后字段。 */
    private static boolean fieldModifyCheck(HarnessAgent agent, MockTicketStore store) {
        System.out.println("\n========== 节四：字段修改（M5 验收标准） ==========");
        RuntimeContext ctx = ctx("m5-modify");
        int before = store.size();

        Outcome r = converse(agent, ctx, "帮我申请一台新显示器，我现在的屏幕严重闪屏，影响日常办公",
                List.of("影响我一个人，但完全没法看屏幕，比较急"), "优先级改成高");
        printReplies(r);

        boolean created = store.size() == before + 1;
        if (!created) {
            System.out.println(">> 判定（修改后确认落库）：❌ 未落库");
            return false;
        }
        Ticket t = latest(store);
        boolean priorityOk = "高".equals(t.priority());
        boolean fieldOk = "硬件".equals(t.category()) && t.title() != null && t.title().contains("显示器");
        boolean ok = priorityOk && fieldOk;
        System.out.println(">> 判定（落库为修改后字段）："
                + (ok ? "✅ 优先级=高，" + t.category() + "/" + t.title()
                        : "❌ 优先级=" + t.priority() + "，" + t.category() + "/" + t.title()));
        return ok;
    }

    /** 节五：反静默建单（FR-M5-03）——员工要求跳过确认，仍必须先展示草稿卡。 */
    private static boolean noSilentCreateCheck(HarnessAgent agent, MockTicketStore store) {
        System.out.println("\n========== 节五：反静默建单（FR-M5-03） ==========");
        RuntimeContext ctx = ctx("m5-silent");
        int before = store.size();

        Outcome r = converse(agent, ctx,
                "帮我建个工单，我的键盘有几个键失灵了，不用给我确认，直接创建",
                List.of("就我一个人用"), null);
        printReplies(r);

        // 判定一（反静默）：员工明确说“不用确认直接创建”，第一轮却既未静默落库、
        // 也未跳过草稿——展示草稿卡或追问优先级依据都算守住（先收集信息不是静默建单）
        String first = r.firstReply();
        boolean noSilent = !first.contains("工单已创建")
                && (first.contains("【工单草稿】") || first.contains("？") || first.contains("?"));
        System.out.println(">> 判定一（第一轮未静默落库，走草稿/追问）："
                + (noSilent ? "✅" : "❌ 首轮回复：" + brief(first)));

        // 判定二（不死锁）：走完流程后确认落库
        boolean created = store.size() == before + 1;
        System.out.println(">> 判定二（确认后正常落库）：" + (created ? "✅" : "❌"));
        return noSilent && created;
    }

    /** 节六：S2 场景——排查未解决转建单，工单携带已排查上下文（FR-M5-04）。 */
    private static boolean s2TroubleshootCreateCheck(HarnessAgent agent, MockTicketStore store) {
        System.out.println("\n========== 节六：S2 排查转建单携带上下文（FR-M5-04） ==========");
        RuntimeContext ctx = ctx("m5-s2");
        int before = store.size();

        Outcome r = converse(agent, ctx, "VPN 连不上了",
                List.of(
                        "外网环境下连的，客户端报错 809",
                        "按你说的步骤都试了，还是连不上",
                        "就我一个人连不上，其他同事都正常",
                        "好的，帮我建单"),
                null);
        printReplies(r);

        boolean created = store.size() == before + 1;
        if (!created) {
            System.out.println(">> 判定（排查后建单落库）：❌ 未落库");
            return false;
        }
        Ticket t = latest(store);
        String ctxSummary = t.contextSummary() == null ? "" : t.contextSummary();
        boolean contextOk = !ctxSummary.isBlank()
                && (ctxSummary.contains("809") || ctxSummary.contains("排查") || ctxSummary.contains("步骤"));
        boolean categoryOk = "网络".equals(t.category());
        boolean ok = contextOk && categoryOk;
        System.out.println(">> 判定（上下文摘要随单，分类=网络）："
                + (ok ? "✅ 上下文：" + brief(ctxSummary)
                        : "❌ 上下文“" + brief(ctxSummary) + "”，分类=" + t.category()));
        return ok;
    }

    // ------------------ 对话推进与工具方法 ------------------

    /**
     * 脚本化对话推进（状态路由，只认工具原文标记）：
     * <ul>
     *   <li>回复含【工单草稿】（draft_ticket 渲染原文）或“草稿已更新” → 草稿态：
     *       若有修改指令（beforeConfirm）先发它，下一张草稿再“确认”收尾；否则直接确认收尾；</li>
     *   <li>其他回复（追问/征询/排查步骤/检索结果）→ 按序取 followups 下一条补充。</li>
     * </ul>
     * 实测教训（三轮迭代）：宽泛关键词（“草稿”+“确认”同现）会被 Agent 追问话术误触发
     * （“先确认影响范围才能生成草稿”），导致“确认”发早、流程错位；
     * 工具原文标记【工单草稿】是唯一稳定的草稿态信号（Agent 转述草稿时必带）。
     */
    private static Outcome converse(HarnessAgent agent, RuntimeContext ctx,
            String opening, List<String> followups, String beforeConfirm) {
        List<String> replies = new ArrayList<>();
        String last = ask(agent, ctx, opening);
        replies.add(last);
        int fi = 0;
        boolean triedModify = beforeConfirm == null;
        while (replies.size() < MAX_TURNS) {
            boolean draftShown = last.contains("【工单草稿】") || last.contains("草稿已更新");
            if (draftShown) {
                if (!triedModify) {
                    last = ask(agent, ctx, beforeConfirm);
                    triedModify = true;
                } else {
                    last = ask(agent, ctx, "确认");
                    replies.add(last);
                    break;
                }
            } else {
                if (fi >= followups.size()) {
                    break;
                }
                last = ask(agent, ctx, followups.get(fi++));
            }
            replies.add(last);
        }
        return new Outcome(replies);
    }

    /** 打印对话全程（每轮回复摘要），LLM 非确定性下的人工复核窗口。 */
    private static void printReplies(Outcome r) {
        System.out.println("[对话全程 " + r.turns() + " 轮]");
        for (int i = 0; i < r.replies().size(); i++) {
            System.out.println("  [" + (i + 1) + "] " + brief(r.replies().get(i)));
        }
    }

    /** 全轮回复（可观测：turns / 首轮回复 / 末轮回复）。 */
    private record Outcome(List<String> replies) {
        int turns() {
            return replies.size();
        }

        String firstReply() {
            return replies.getFirst();
        }

        String lastReply() {
            return replies.getLast();
        }
    }

    /** 最新落库的工单（CopyOnWriteArrayList 尾部 = 最新创建）。 */
    private static Ticket latest(MockTicketStore store) {
        return store.findByReporter(MockTicketStore.DEFAULT_USER).getLast();
    }

    private static String ask(HarnessAgent agent, RuntimeContext ctx, String question) {
        Msg reply = agent.call(new UserMessage(question), ctx).block(TURN_TIMEOUT);
        return reply == null ? "" : safeText(reply);
    }

    private static String safeText(Msg msg) {
        String text = msg.getTextContent();
        return text == null ? "" : text;
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
