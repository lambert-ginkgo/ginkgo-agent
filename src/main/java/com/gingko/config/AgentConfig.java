package com.gingko.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 集中管理 Agent 配置（FR-M1-03：API Key、模型名外置，不硬编码）。
 * 优先级：环境变量 > ./config/application.properties > classpath 默认值。
 */
public record AgentConfig(String apiKey, String baseUrl, String modelName) {

    private static final Path LOCAL_CONFIG = Path.of("config", "application.properties");

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

        if (apiKey == null) {
            throw new ConfigException("""
                    未找到模型 API Key，Agent 无法启动。请任选一种方式配置：
                      1. 设置环境变量 DEEPSEEK_API_KEY
                      2. 复制 config/application.properties.example 为 config/application.properties，填写 ginkgo.model.api-key
                    """);
        }
        return new AgentConfig(apiKey, baseUrl, modelName);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
