package com.gingko.dev;

import com.gingko.config.AgentConfig;
import com.gingko.knowledge.KnowledgeService;
import com.gingko.knowledge.KnowledgeService.ImportStats;
import io.agentscope.core.rag.model.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M4 知识库问答自动化验收（非交互，检索层，PRD M4 验收标准）：
 *
 * <ol>
 *   <li>FR-M4-01/02 导入：知识库目录全量导入，打印文档数与切分片段数</li>
 *   <li>S1 直答（检索层）：『密码忘了怎么重置』命中 password-reset.md；『VPN 连不上』命中 vpn 文档</li>
 *   <li>FR-M4-04 未命中：离题问题返回空（低于相似度阈值），工具输出包含明确的未命中话术</li>
 *   <li>FR-M4-02 增量生效：新放一个文档 → reload → 新问题立即命中，随后清理还原</li>
 *   <li>FR-M4-03/05 输出契约：工具输出含出处文件名与相似度分数</li>
 * </ol>
 *
 * <p>只验证检索层（embedding API），不调用对话模型。全链路（Agent 决定何时调工具、
 * 答案是否带出处）由 Main 交互验证（用户本人执行）。
 *
 * <p>运行：{@code mvn compile exec:java -Dexec.mainClass=com.gingko.dev.KnowledgeAcceptanceRun}
 */
public final class KnowledgeAcceptanceRun {

    public static void main(String[] args) {
        AgentConfig config;
        try {
            config = AgentConfig.load();
        } catch (AgentConfig.ConfigException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
            return;
        }
        if (!config.embeddingConfigured()) {
            System.err.println("[启动失败] 未配置 embedding API Key（EMBEDDING_API_KEY 或 ginkgo.embedding.api-key）。");
            System.exit(1);
            return;
        }

        KnowledgeService knowledge = KnowledgeService.create(config);

        // ===== ① 导入（FR-M4-01/02） =====
        System.out.println("========== 验证一：文档导入与切分（FR-M4-01/02） ==========");
        ImportStats stats = knowledge.importAll();
        System.out.println("[导入] " + stats.summary());
        System.out.println(">> 判定：" + (knowledge.isReady() ? "✅ 导入成功" : "❌ 导入失败：" + stats.error()) + "\n");

        // ===== ② 检索命中（S1 直答场景） =====
        System.out.println("========== 验证二：检索命中（S1 场景，FR-M4-03） ==========");
        checkHit(knowledge, "密码忘了怎么重置？", "password-reset.md");
        checkHit(knowledge, "在家办公 VPN 连不上怎么办？", "vpn-troubleshooting.md");
        checkHit(knowledge, "邮箱满了收不了邮件", "email-quota.md");
        checkHit(knowledge, "打印机卡住没反应", "printer-issues.md");
        System.out.println();

        // ===== ③ 未命中（FR-M4-04） =====
        System.out.println("========== 验证三：离题问题未命中（FR-M4-04） ==========");
        List<Document> miss = knowledge.search("附近有什么好吃的餐厅推荐？");
        System.out.println("[检索] 附近有什么好吃的餐厅推荐？ → " + miss.size() + " 条结果");
        System.out.println(">> 判定：" + (miss.isEmpty() ? "✅ 未命中（低于阈值，不硬凑）" : "❌ 离题问题竟然命中了："
                + miss.get(0).getPayloadValue("filename")) + "\n");

        // ===== ④ 增量导入立即生效（FR-M4-02） =====
        System.out.println("========== 验证四：新增文档 reload 后立即命中（FR-M4-02） ==========");
        Path tempDoc = KnowledgeService.KNOWLEDGE_DIR.resolve("device-apply.md");
        try {
            Files.writeString(tempDoc, """
                    # 外设申请 FAQ

                    ## Q：怎么申请外接显示器？

                    显示器、键盘、鼠标等外设由行政统一采购。申请入口：内部门户 → 行政服务 → 外设申请。

                    填写内容：工号、部门、需求说明（岗位需要双屏等）、期望型号（可不填，默认采购标准款）。

                    审批流：直属主管 → 部门负责人 → 行政。标准件 3 个工作日内发放到工位；特殊型号需要额外采购周期。

                    入职未满 6 个月的新员工只能申请标准单屏配置。
                    """);
            ImportStats reloaded = knowledge.reload();
            System.out.println("[reload] " + reloaded.summary());
            checkHit(knowledge, "想申请一个外接显示器怎么操作？", "device-apply.md");
        } catch (Exception e) {
            System.out.println(">> 判定：❌ 增量验证执行失败 - " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tempDoc);
                knowledge.reload();
                System.out.println("[清理] 临时文档已删除，知识库已还原（" + knowledge.stats().summary() + "）");
            } catch (Exception e) {
                System.out.println("[清理] 还原失败，请手动删除 " + tempDoc + " 后 /kb reload");
            }
        }
        System.out.println();

        // ===== ⑤ 工具输出契约（FR-M4-03/05） =====
        System.out.println("========== 验证五：工具输出契约（出处 + 分数 + 未命中话术） ==========");
        System.out.println("---- search_knowledge(\"VPN 连不上怎么办\") 输出 ----");
        System.out.println(knowledge.tools().searchKnowledge("VPN 连不上怎么办"));
        System.out.println("---- search_knowledge(\"附近有什么好吃的餐厅\") 输出 ----");
        System.out.println(knowledge.tools().searchKnowledge("附近有什么好吃的餐厅"));
    }

    /** 检索命中断言：Top1 片段的来源文件应为期望文档。 */
    private static void checkHit(KnowledgeService knowledge, String query, String expectedFile) {
        List<Document> hits = knowledge.search(query);
        if (hits.isEmpty()) {
            System.out.println("[检索] " + query + " → 未命中（期望命中 " + expectedFile + "）");
            System.out.println(">> 判定：❌ 应命中未命中");
            return;
        }
        Document top = hits.get(0);
        String file = String.valueOf(top.getPayloadValue("filename"));
        System.out.printf("[检索] %s → Top1：%s（相似度 %.3f）%n", query, file, top.getScore());
        System.out.println(">> 判定：" + (file.equals(expectedFile) ? "✅ 命中正确来源" : "❌ 来源不符，期望 " + expectedFile));
    }
}
