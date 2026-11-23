package com.datakhaos.pipeline.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;


/**
 * 管道执行线程池 + 引擎配置
 */
@Configuration
public class PipelineConfig {

    @Value("${data-khaos.pipeline.core-pool-size:2}")
    private int corePoolSize;
    @Value("${data-khaos.pipeline.max-pool-size:8}")
    private int maxPoolSize;
    @Value("${data-khaos.pipeline.queue-capacity:100}")
    private int queueCapacity;

    @Bean("pipelineTaskExecutor")
    @Primary
    public ThreadPoolTaskExecutor pipelineTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("pipeline-run-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            if (pool.isShutdown()) throw new java.util.concurrent.RejectedExecutionException("管道线程池已关闭");
            task.run();
        });
        executor.initialize();
        return executor;
    }
    @Bean("workspacePipelineExecutor")
    public ThreadPoolTaskExecutor workspacePipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("workspace-pipeline-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(45);
        return executor;
    }
}