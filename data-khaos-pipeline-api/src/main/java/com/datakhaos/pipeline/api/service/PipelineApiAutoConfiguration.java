package com.datakhaos.pipeline.api.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * 数据管道客户端自动装配（依赖 common 提供的 orchestrationRestTemplate）。
 */
@AutoConfiguration
@ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.cloud.client.loadbalancer.LoadBalanced"})
public class PipelineApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PipelineApiClient pipelineApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        return new PipelineApiClient(restTemplate);
    }
}
