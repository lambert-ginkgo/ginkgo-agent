package com.gingko.auth;

import java.util.Map;

/**
 * mock 身份源（M7，FR-M7-01）：userId → 用户（角色）。
 * 真实环境对接企业目录（LDAP/OA），E09 随 IM 接入换成平台身份映射（FR-M9-03）。
 *
 * <p>未注册身份的兜底策略是安全设计而非偷懒：<b>fail-safe 给最低权限</b>——
 * 未知身份按员工处理（只有本人工单数据范围），绝不默认放行管理员能力。
 * 权限体系里"认不出的用户"应该少拿权限，而不是多拿。
 */
public final class UserDirectory {

    private static final Map<String, User> USERS = Map.of(
            "cli-user", new User("cli-user", "刘工", User.Role.EMPLOYEE),
            "zhangsan", new User("zhangsan", "张三", User.Role.EMPLOYEE),
            "it-admin", new User("it-admin", "张工", User.Role.IT_ADMIN));

    private UserDirectory() {
    }

    /** 按会话 userId 解析身份；未注册身份 fail-safe 为员工（最低权限）。 */
    public static User resolve(String userId) {
        User known = USERS.get(userId);
        if (known != null) {
            return known;
        }
        return new User(userId, userId, User.Role.EMPLOYEE);
    }
}
