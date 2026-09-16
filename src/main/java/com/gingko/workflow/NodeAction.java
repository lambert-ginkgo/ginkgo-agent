package com.gingko.workflow;

import java.util.Map;

/**
 * 函数节点动作（M6，FR-M6-01）：接收全局状态，返回状态更新。
 *
 * <p>概念对齐（v1 官方文档的 NodeAction，手搓实现）：AgentScope Java 2.0.3 的
 * core 包里没有任何图编排原语（487 个类无一与 graph/pipeline/workflow 相关），
 * v1 文档展示的 StateGraph/SequentialAgent 等编排类来自 Spring AI Alibaba
 * （{@code com.alibaba.cloud.ai.graph.*} 包），且其示例模块已在 2.0 包重构中移除。
 * 「把流程固化为确定性工作流」在本框架里没有现成轮子——本包就是手搓的那只轮子。
 *
 * <p>节点分两类（混合确定性与智能，这是工作流编排的核心价值）：
 * <ul>
 *   <li><b>确定性节点</b>：纯 Java 逻辑（检索、落库、清理），无 LLM——
 *       代码保证必发生，不依赖模型自觉；</li>
 *   <li><b>智能节点</b>：内部调用 LLM（意图分类、答复组装、字段抽取）——
 *       智能只出现在节点内部，节点之间的流转永远是确定的。</li>
 * </ul>
 */
@FunctionalInterface
public interface NodeAction {

    /**
     * 执行节点逻辑。
     *
     * @param state 当前全局状态（可读）
     * @return 状态更新（合并进全局状态；无更新返回空 Map）
     * @throws Exception 节点失败（图执行终止，异常进轨迹）
     */
    Map<String, Object> apply(WorkflowState state) throws Exception;
}
