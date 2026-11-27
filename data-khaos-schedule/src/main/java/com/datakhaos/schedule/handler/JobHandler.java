package com.datakhaos.schedule.handler;

/**
 * 任务执行 SPI：新增任务类型只需实现本接口并注册为 Spring Bean，
 * 调度执行器不再需要改 switch。
 */
public interface JobHandler {

    /** 任务类型，大小写不敏感（SQL / QUALITY / WORKFLOW / SYNC / FLINK）。 */
    String type();

    /**
     * 执行任务。
     *
     * @return 结果行数（无行数语义时返回 1）
     */
    int execute(JobContext context);
}
