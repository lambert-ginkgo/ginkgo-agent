package com.gingko.auth;

/**
 * M7 权限判定中枢（FR-M7-02/03）：数据行级 + 工具级放行。
 *
 * <p>为什么手搓而不等官方引擎（E07 三源核实结论，写作素材）：
 * <ul>
 *   <li>AgentScope core 2.0.3 有完整的 {@code io.agentscope.core.permission} 包
 *       （PermissionEngine 六步判定：deny → ask → tool-specific → allow →
 *       BYPASS → default），但它的判定粒度是<b>工具名</b>（PermissionRule 按
 *       toolName 匹配）——管的是"这个工具能不能调"，不管"调了之后能看到哪几行数据"；</li>
 *   <li>HarnessAgent.Builder 没有 permissionContext 挂载点（方法清单实证），
 *       默认 trivial（未 opt-in）走轻量路径——我们 E01-E06 一直在权限系统未启用的状态下跑；</li>
 *   <li>「员工 A 查不到员工 B 的单子」是<b>数据行级</b>权限（同一工具、不同参数、
 *       不同可见行），框架的规则引擎表达不了，只能落在工具内部——按当次调用的
 *       RuntimeContext 身份过滤。这也是 PRD M7 验收标准"工具层拦截而非仅提示词
 *       约束"的落点。</li>
 * </ul>
 *
 * <p>能力级权限（哪些工具可用）交给官方引擎的场景：EXPLORE 只读模式
 * （readOnly=true 的工具自动放行，写操作 DENY）——见 Main 的 /perm 命令与
 * M7AcceptanceRun 节五，{@code @Tool(readOnly = true)} 注解在 2.0.3 里的
 * 唯一判定入口就在那里。
 */
public final class TicketPermission {

    private TicketPermission() {
    }

    /** 数据行级（FR-M7-02）：员工仅本人工单；IT 管理员全量。 */
    public static boolean canView(User user, String reporter) {
        return user.isAdmin() || user.userId().equals(reporter);
    }

    /** 工具级放行（FR-M7-03）：工单管理操作（改状态/关单/全量列表）仅 IT 管理员。 */
    public static boolean canManage(User user) {
        return user.isAdmin();
    }

    /** 数据越权拦截话术（FR-M7-03：拦截必须有明确话术，让模型能转述而不是装死）。 */
    public static String denyView(String ticketId, User user) {
        return "[无权访问] 工单 " + ticketId + " 不属于当前用户 " + user.display()
                + "——员工仅能查看本人工单，IT 管理员可查看全部。"
                + "请如实告知用户无权查看该工单；如确需代查，建议用户联系 IT 服务台。";
    }

    /** 管理操作拦截话术（FR-M7-03）。 */
    public static String denyManage(String operation, User user) {
        return "[操作被拒] " + operation + " 是 IT 管理员操作，当前用户 " + user.display()
                + " 无权执行。请如实告知用户该操作需要 IT 工程师处理，"
                + "建议联系 IT 服务台或让用户等待工程师处理。不要尝试以其他方式绕过权限。";
    }
}
