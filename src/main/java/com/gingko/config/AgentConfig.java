package com.gingko.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 集中管理 Agent 配置（FR-M1-03：API Key、模型名外置，不硬编码）。
 * 优先级：环境变量 > ./config/application.properties > classpath 默认值。
 *
 * <p>E04 起新增 embedding 配置组（FR-M4-02）：DeepSeek 官方 API 不提供
 * embeddings 端点，向量模型必须走另一家 OpenAI 兼容服务，与对话模型解耦配置。
 * embedding Key 未配置时不阻断启动——知识库问答功能降级关闭，其余功能照常（向后兼容 E01-E03）。
 */
public record AgentConfig(
        String apiKey,
        String baseUrl,
        String modelName,
        String embeddingApiKey,
        String embeddingBaseUrl,
        String embeddingModelName,
        int embeddingDimensions) {

    private static final Path LOCAL_CONFIG = Path.of("config", "application.properties");

    /** 默认指向阿里云百炼 OpenAI 兼容端点；换硅基流动/Ollama 等兼容服务时改配置即可。 */
    private static final String DEFAULT_EMBEDDING_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1024;

    /** embedding 是否已配置（决定知识库问答是否可用）。 */
    public boolean embeddingConfigured() {
        return embeddingApiKey != null;
    }

    public static AgentConfig load() {
        Properties props = new Properties();

        try (InputStream in = AgentConfig.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new ConfigException("读取内置配置失败: " + e.getMessage());
        }

        if (Files.exists(LOCAL_CONFIG)) {
            try (InputStream in = Files.newInputStream(LOCAL_CONFIG)) {
                props.load(in);
            } catch (IOException e) {
                throw new ConfigException(
                        "读取配置文件失败: " + LOCAL_CONFIG.toAbsolutePath() + " (" + e.getMessage() + ")");
            }
        }

        String apiKey = firstNonBlank(System.getenv("DEEPSEEK_API_KEY"), props.getProperty("ginkgo.model.api-key"));
        String baseUrl = firstNonBlank(
                System.getenv("DEEPSEEK_BASE_URL"), props.getProperty("ginkgo.model.base-url"), "https://api.deepseek.com");
        String modelName = firstNonBlank(
                System.getenv("DEEPSEEK_MODEL"), props.getProperty("ginkgo.model.name"), "deepseek-chat");

        String embeddingApiKey = firstNonBlank(
                System.getenv("EMBEDDING_API_KEY"), props.getProperty("ginkgo.embedding.api-key"));
        String embeddingBaseUrl = firstNonBlank(
                System.getenv("EMBEDDING_BASE_URL"),
                props.getProperty("ginkgo.embedding.base-url"),
                DEFAULT_EMBEDDING_BASE_URL);
        String embeddingModelName = firstNonBlank(
                System.getenv("EMBEDDING_MODEL"),
                props.getProperty("ginkgo.embedding.model-name"),
                DEFAULT_EMBEDDING_MODEL);
        int embeddingDimensions = parseIntOrDefault(
                firstNonBlank(
                        System.getenv("EMBEDDING_DIMENSIONS"),
                        props.getProperty("ginkgo.embedding.dimensions")),
                DEFAULT_EMBEDDING_DIMENSIONS,
                "ginkgo.embedding.dimensions");

        if (apiKey == null) {
            throw new ConfigException("""
                    未找到模型 API Key，Agent 无法启动。请任选一种方式配置：
                      1. 设置环境变量 DEEPSEEK_API_KEY
                      2. 复制 config/application.properties.example 为 config/application.properties，填写 ginkgo.model.api-key
                    """);
        }
        return new AgentConfig(
                apiKey, baseUrl, modelName,
                embeddingApiKey, embeddingBaseUrl, embeddingModelName, embeddingDimensions);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private static int parseIntOrDefault(String value, int defaultValue, String configKey) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[配置警告] " + configKey + " 不是合法数字（" + value + "），使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
