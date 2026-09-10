package com.gingko.knowledge;

import com.gingko.config.AgentConfig;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextChunker;
import io.agentscope.core.rag.store.InMemoryStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 知识库服务（M4，E04）：文档导入、向量化入库与检索的装配中心。
 *
 * <p>为什么自己写导入而不用框架的 {@code TextReader}：官方 TextReader 构造的
 * Document 元数据不带文件名（无 payload），检索结果无从标注出处；而 FR-M4-03
 * 要求答案附带出处（文档名/片段定位）。本导入器以文件名为稳定 docId，
 * 每个 chunk 的 payload 携带 filename，检索结果可溯源。
 *
 * <p>存储用进程内 {@link InMemoryStore}（重启即重建，导入器保持幂等全量重建语义），
 * 符合 PRD Q3"避免 MVP 引入独立向量库运维"的约束。
 */
public final class KnowledgeService {

    /** 知识库文档目录：往这里放 .md 文件即完成"导入"（FR-M4-01）。 */
    public static final Path KNOWLEDGE_DIR = Path.of("knowledge");

    /** 切分参数：PARAGRAPH 策略下 chunkSize 为每块最大字符数，overlap 为块间重叠字符数。 */
    static final int CHUNK_SIZE = 512;
    static final int CHUNK_OVERLAP = 50;

    /** 检索参数（FR-M4-05 可观测）：低于相似度阈值视为未命中。 */
    static final int TOP_K = 3;
    static final double SCORE_THRESHOLD = 0.45;

    private final InMemoryStore store;
    private final Knowledge knowledge;
    private final Path knowledgeDir;

    private volatile ImportStats lastImport = new ImportStats(0, 0, 0, true, null);

    private KnowledgeService(InMemoryStore store, Knowledge knowledge, Path knowledgeDir) {
        this.store = store;
        this.knowledge = knowledge;
        this.knowledgeDir = knowledgeDir;
    }

    /**
     * 装配知识库：OpenAI 兼容 embedding 模型 + 进程内向量存储 + SimpleKnowledge。
     * 注意 embedding 与对话模型是两个独立服务（DeepSeek 无 embeddings 端点），
     * baseUrl 指向任一 OpenAI 兼容向量服务即可。
     */
    public static KnowledgeService create(AgentConfig config) {
        EmbeddingModel embeddingModel = OpenAITextEmbedding.builder()
                .apiKey(config.embeddingApiKey())
                .baseUrl(config.embeddingBaseUrl())
                .modelName(config.embeddingModelName())
                .dimensions(config.embeddingDimensions())
                .build();
        InMemoryStore store = InMemoryStore.builder()
                .dimensions(config.embeddingDimensions())
                .build();
        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
        return new KnowledgeService(store, knowledge, KNOWLEDGE_DIR);
    }

    /**
     * 全量导入知识库目录下的 .md 文档（FR-M4-01/02）：
     * 读取 → TextChunker 按段落切分（512 字符 + 50 重叠）→ 构造带出处的 Document → 向量化入库。
     */
    public ImportStats importAll() {
        long start = System.currentTimeMillis();
        if (!Files.isDirectory(knowledgeDir)) {
            lastImport = new ImportStats(0, 0, System.currentTimeMillis() - start, false,
                    "知识库目录不存在：" + knowledgeDir.toAbsolutePath());
            return lastImport;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(knowledgeDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            lastImport = new ImportStats(0, 0, System.currentTimeMillis() - start, false,
                    "扫描知识库目录失败: " + e.getMessage());
            return lastImport;
        }

        List<Document> documents = new ArrayList<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            try {
                String content = Files.readString(file);
                // docId 用文件名（稳定）：同一文件重复导入时内容未变的 chunk 因确定性 ID 天然覆盖
                List<String> chunks = TextChunker.chunkText(
                        content, CHUNK_SIZE, SplitStrategy.PARAGRAPH, CHUNK_OVERLAP);
                for (int i = 0; i < chunks.size(); i++) {
                    DocumentMetadata metadata = DocumentMetadata.builder()
                            .content(TextBlock.builder().text(chunks.get(i)).build())
                            .docId(filename)
                            .chunkId(String.valueOf(i))
                            .addPayload("filename", filename)
                            .addPayload("chunkIndex", i)
                            .addPayload("totalChunks", chunks.size())
                            .build();
                    documents.add(new Document(metadata));
                }
            } catch (IOException e) {
                lastImport = new ImportStats(0, 0, System.currentTimeMillis() - start, false,
                        "读取文档失败: " + filename + " (" + e.getMessage() + ")");
                return lastImport;
            }
        }

        try {
            knowledge.addDocuments(documents).block();
        } catch (RuntimeException e) {
            lastImport = new ImportStats(0, 0, System.currentTimeMillis() - start, false,
                    "向量化入库失败（embedding API 不可用或被限流）: " + rootMessage(e));
            return lastImport;
        }

        lastImport = new ImportStats(files.size(), documents.size(), System.currentTimeMillis() - start, true, null);
        return lastImport;
    }

    /**
     * 重新加载知识库（FR-M4-02 验收依赖：更新文档后新问题能立即命中新内容）。
     * 全量重建语义：先清空再导入——文档内容变更后旧 chunk 的确定性 ID 会变化，
     * 不清空会导致新旧片段共存，答案混入过期内容。
     */
    public ImportStats reload() {
        store.clear();
        return importAll();
    }

    /** 检索：query 向量化后与库内 chunk 做 cosine 相似度匹配，低于阈值的结果被过滤。 */
    public List<Document> search(String query) {
        return knowledge
                .retrieve(query, RetrieveConfig.builder()
                        .limit(TOP_K)
                        .scoreThreshold(SCORE_THRESHOLD)
                        .build())
                .block();
    }

    /** 知识库是否就绪（导入成功且非空）。 */
    public boolean isReady() {
        return lastImport.success() && lastImport.chunks() > 0;
    }

    public ImportStats stats() {
        return lastImport;
    }

    public KnowledgeTools tools() {
        return new KnowledgeTools(this);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    /**
     * 导入统计：文档数、chunk 数、耗时（含 embedding API 调用）与失败原因。
     */
    public record ImportStats(int documents, int chunks, long elapsedMs, boolean success, String error) {

        public String summary() {
            if (!success) {
                return "导入失败：" + error;
            }
            return "已导入 " + documents + " 个文档，切分为 " + chunks + " 个知识片段（耗时 " + elapsedMs + " ms）";
        }
    }
}
