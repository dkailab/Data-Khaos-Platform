package com.datakhaos.workflow.executor;

/**
 * 节点类型常量
 */
public final class NodeType {

    private NodeType() {
    }

    /** SQL 脚本节点 */
    public static final String SQL = "SQL";
    /** Shell 脚本节点 */
    public static final String SHELL = "SHELL";
    /** Python 脚本节点 */
    public static final String PYTHON = "PYTHON";
    /** 数据算子节点（格式化/加工） */
    public static final String DATA_OP = "DATA_OP";
    /** 管道任务节点（触发 pipeline 并等待完成） */
    public static final String PIPELINE = "PIPELINE";
    /** 质量稽核节点（触发质量任务并阻塞门禁） */
    public static final String QUALITY = "QUALITY";
    /** 延时器节点（等待固定秒数后继续） */
    public static final String DELAY = "DELAY";
    /** 嵌套任务流节点（触发子任务流并等待终态） */
    public static final String NESTED = "NESTED";
}