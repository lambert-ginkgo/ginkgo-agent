package com.gingko.knowledge;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;

/**
 * 知识库检索工具（M4，E04）：Agent 继工单查询之后的第二个业务工具。
 *
 * <p>为什么不用框架自带的 {@code KnowledgeRetrievalTools}：官方工具的输出只含
 * 相似度分数与片段内容、不含出处文件，无法满足 FR-M4-03"答案附带出处"；
 * 且其未命中时返回英文 "No relevant documents found"，对中文服务台话术不友好。
 * 自写工具可控输出契约：出处（文件名）+ 相似度（FR-M4-05 可观测）+ 中文未命中话术（FR-M4-04）。
 */
public class KnowledgeTools {

    private final KnowledgeService service;

    public KnowledgeTools(KnowledgeService service) {
        this.service = service;
    }

    @Tool(name = "search_knowledge",
            description = "检索企业 IT 知识库（常见问题文档），返回相关片段及其来源文件。"
                    + "员工咨询 IT 使用或故障类问题（密码重置、账号锁定、VPN 连接、邮箱、打印机等）时，"
                    + "先调用本工具检索，再基于返回内容回答。",
            readOnly = true,
            concurrencySafe = true)
    public String searchKnowledge(
            @ToolParam(name = "query", description = "要检索的问题或关键词，如：VPN 连不上怎么办") String query) {
        // 检索可观测（M10 前置）：模型传参可能与用户原话不同（ReAct 自主决定 query），留痕便于排查
        System.err.println("[search_knowledge] query=" + query);
        List<Document> hits;
        try {
            hits = service.search(query);
        } catch (RuntimeException e) {
            // NFR-03：工具失败明确报错，不静默吞掉——让模型告知用户检索暂时不可用
            return "知识库检索失败（" + e.getMessage() + "）。请告知用户知识库服务暂时不可用，稍后再试，或直接创建工单。";
        }

        if (hits == null || hits.isEmpty()) {
            // FR-M4-04：未命中必须显式说"知识库里没有"，禁止编造
            return "知识库未命中：没有检索到与「" + query + "」相关的知识片段"
                    + "（最高相似度低于阈值 " + KnowledgeService.SCORE_THRESHOLD + "）。"
                    + "请如实告知用户知识库中暂无该问题的资料，不要编造解决步骤；"
                    + "可建议用户创建工单（后续版本支持）或联系 IT 服务台。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("从知识库检索到 ").append(hits.size()).append(" 条相关片段：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            Document doc = hits.get(i);
            String filename = String.valueOf(doc.getPayloadValue("filename"));
            sb.append("[").append(i + 1).append("] 来源：").append(filename)
                    .append("（相似度 ").append(String.format("%.3f", doc.getScore())).append("）\n")
                    .append(doc.getMetadata().getContentText().strip())
                    .append("\n\n");
        }
        sb.append("请基于以上片段回答用户问题，并注明出处文件名；片段未覆盖的部分不要编造。");
        return sb.toString();
    }
}
