package com.datakhaos.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 注册中心感知的负载均衡 RestTemplate。
 * 网关（WebFlux，无 servlet）不会加载本配置。
 * 下游服务客户端（DatasourceApiClient / PermissionApiClient）由各 api 模块自行装配。
 */
@AutoConfiguration
@ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.cloud.client.loadbalancer.LoadBalanced"})
public class KhaosAutoConfiguration {

    @Bean("orchestrationRestTemplate")
    @LoadBalanced
    @ConditionalOnMissingBean(name = "orchestrationRestTemplate")
    public RestTemplate orchestrationRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    @Primary
    @Bean("lbRestTemplate")
    @LoadBalanced
    @ConditionalOnMissingBean(name = "lbRestTemplate")
    public RestTemplate lbRestTemplate() {
        return new RestTemplate();
    }
}
