package com.gingko.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 扫描 AgentScope 默认状态目录，列出某用户的历史会话（支撑 /sessions 与 /resume）。
 *
 * <p>磁盘布局与 JsonFileAgentStateStore 一致：
 * {@code ~/.agentscope/state/<agentName>/<userId>/<sessionId>/agent_state.json}。
 * 会话按最后修改时间倒序排列，最新在前。
 */
public final class SessionHistory {

    /** 历史会话条目：sessionId + 状态文件最后修改时间。 */
    public record SessionInfo(String sessionId, Instant lastModified) {
    }

    private SessionHistory() {
    }

    /** 列出 userId 名下的全部历史会话（最新在前）；目录不存在时返回空列表。 */
    public static List<SessionInfo> list(String agentName, String userId) {
        Path userDir = Path.of(System.getProperty("user.home"), ".agentscope", "state", agentName, userId);
        if (!Files.isDirectory(userDir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(userDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("agent_state.json")))
                    .map(d -> {
                        try {
                            return new SessionInfo(
                                    d.getFileName().toString(),
                                    Files.getLastModifiedTime(d).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(s -> s != null)
                    .sorted(Comparator.comparing(SessionInfo::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
