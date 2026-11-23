package com.datakhaos.pipeline.engine;

import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;

/**
 * 管道执行引擎 SPI。
 *
 * <p>新增引擎：实现本接口，并在 {@link EngineFactory} 中注册即可，无需改动主流程。
 * 前端「引擎列表」接口会自动返回新引擎。</p>
 */
public interface PipelineEngine {

    /** 引擎标识：DB_SYNC / DATAX / SEATUNNEL（新增在此扩展） */
    String type();

    /** 引擎名称（展示用） */
    String name();

    /** 引擎是否可用（例如引擎 CLI 是否安装） */
    boolean available();

    /** 由任务定义生成引擎运行说明 / 配置摘要（JSON 字符串） */
    String buildRunConfig(PipelineTask task);

    /**
     * 同步执行一次任务，返回影响行数；抛异常表示失败。
     */
    int execute(PipelineTask task, PipelineInstance instance) throws Exception;

    /** JDBC could not confirm the outcome of a transaction. A retry requires reconciliation. */
    class CommitUnconfirmedException extends Exception {
        public CommitUnconfirmedException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
