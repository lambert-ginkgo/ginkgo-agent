package com.gingko.intent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 意图分类结果（M5，FR-M5-01）：AgentScope 结构化输出的目标类型。
 *
 * <p>record + 嵌套 enum 的组合是实测验证项：官方 v1 文档只承诺
 * “public 字段 POJO + 无参构造”，对 record/enum 只字未提（底层是
 * Victools jsonschema-generator + Jackson，两者对 record/enum 均有原生支持）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketIntent(
        @JsonPropertyDescription("意图分类结果，四选一") Intent intent,
        @JsonPropertyDescription("分类依据，一句话说明判定理由") String reason) {

    /** 四类意图（PRD FR-M5-01）：TROUBLESHOOT 是兜底意图（分类不确定时的默认落点）。 */
    public enum Intent {
        /** 查询：问已有工单的状态、进度、处理人或工单列表。 */
        QUERY_TICKET,
        /** 直答：IT 使用/故障类问题，知识库文档可能直接有答案（如密码重置步骤）。 */
        DIRECT_ANSWER,
        /** 排查：故障/异常类问题，需要交互式收集信息（如 VPN 连不上）。兜底意图。 */
        TROUBLESHOOT,
        /** 建单：申请/变更类需求（如开通权限、领用设备），或明确要求创建工单。 */
        CREATE_TICKET
    }
}
