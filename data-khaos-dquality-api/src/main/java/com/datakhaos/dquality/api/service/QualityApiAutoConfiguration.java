package com.datakhaos.dquality.api.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * 数据质量服务客户端自动装配（依赖 common 提供的 orchestrationRestTemplate）。
 *
 * <p>各 api 客户端位于 {@code com.datakhaos.<module>.api} 包，不在使用方服务的
 * 组件扫描范围内，因此统一通过 AutoConfiguration.imports 装配。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.cloud.client.loadbalancer.LoadBalanced"})
public class QualityApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QualityApiClient qualityApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        return new QualityApiClient(restTemplate);
    }
}
