package com.datakhaos.approval.api.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * 审批客户端自动装配（依赖 common 提供的 orchestrationRestTemplate）。
 */
@AutoConfiguration
@ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.cloud.client.loadbalancer.LoadBalanced"})
public class ApprovalApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApprovalApiClient approvalApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        return new ApprovalApiClient(restTemplate);
    }
}
