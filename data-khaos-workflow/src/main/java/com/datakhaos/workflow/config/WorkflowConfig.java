package com.datakhaos.workflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 工作流节点执行线程池
 */
@Configuration
public class WorkflowConfig {

    /** DAG 协调与节点执行使用不同线程池，防止满负荷时协调线程占用所有节点线程。 */
    @Bean("workflowGraphExecutor")
    public ThreadPoolTaskExecutor workflowGraphExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("workflow-graph-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /** 节点执行线程池：并行的就绪节点在此执行 */
    @Bean("nodeTaskExecutor")
    public ThreadPoolTaskExecutor nodeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("workflow-node-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}