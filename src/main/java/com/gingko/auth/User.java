package com.gingko.auth;

/**
 * 会话用户身份（M7，FR-M7-01）：userId 对应 RuntimeContext 的会话身份，
 * 角色决定数据范围（员工仅本人工单）与工具放行（管理操作仅 IT 管理员）。
 *
 * <p>身份来源的三层演进（写作素材）：E01-E06 CLI 期"我"固定为 mock 默认用户
 * （工具构造时注入），E07 起身份随会话流动（@Tool 方法注入 RuntimeContext，
 * 每次工具调用按当次会话解析），E09 将随 M9 切换为 IM 平台透传。
 */
public record User(String userId, String displayName, Role role) {

    /** 服务台两角色（PRD M7：员工 / IT 工程师）。 */
    public enum Role {
        EMPLOYEE, IT_ADMIN
    }

    public boolean isAdmin() {
        return role == Role.IT_ADMIN;
    }

    /** 展示用身份串（如 "cli-user（员工）"）。 */
    public String display() {
        String roleLabel = isAdmin() ? "IT 管理员" : "员工";
        return userId + "（" + roleLabel + "）";
    }
}
