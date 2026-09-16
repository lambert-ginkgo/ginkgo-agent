package com.gingko.workflow;

/**
 * 图执行异常（M6）：路由返回空/未知节点、步数超限（疑似死循环）等流程级失败。
 * 节点内部的业务异常直接以原异常记录，不包本类。
 */
public class GraphExecutionException extends RuntimeException {

    public GraphExecutionException(String message) {
        super(message);
    }
}
