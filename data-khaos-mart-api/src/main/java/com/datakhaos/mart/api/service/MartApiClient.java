package com.datakhaos.mart.api.service;

import com.datakhaos.common.model.R;
import com.datakhaos.mart.api.model.DimensionDto;
import com.datakhaos.mart.api.model.MetricDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据集市服务 REST 客户端（通过注册中心负载均衡调用 data-khaos-mart）。
 * 供 visual / query 等下游服务获取模型下的指标和维度信息。
 */
@Slf4j
public class MartApiClient {

    public static final String SERVICE_URL = "http://data-khaos-mart/api/mart";

    private final RestTemplate restTemplate;

    public MartApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 获取指定模型下的所有指标列表
     */
    public List<MetricDto> listMetrics(String modelId) {
        try {
            ResponseEntity<R<List<MetricDto>>> resp = restTemplate.exchange(
                    SERVICE_URL + "/query/metrics?modelId=" + modelId,
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<MetricDto>>>() {});
            R<List<MetricDto>> body = resp.getBody();
            if (body != null && body.getCode() == 0 && body.getData() != null) {
                return body.getData();
            }
        } catch (Exception e) {
            log.warn("调用集市服务查询指标失败: modelId={}, error={}", modelId, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 获取指定模型下的所有维度列表
     */
    public List<DimensionDto> listDimensions(String modelId) {
        try {
            ResponseEntity<R<List<DimensionDto>>> resp = restTemplate.exchange(
                    SERVICE_URL + "/query/dimensions?modelId=" + modelId,
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<DimensionDto>>>() {});
            R<List<DimensionDto>> body = resp.getBody();
            if (body != null && body.getCode() == 0 && body.getData() != null) {
                return body.getData();
            }
        } catch (Exception e) {
            log.warn("调用集市服务查询维度失败: modelId={}, error={}", modelId, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 模型详情（含指标/维度/关联）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> modelDetail(String modelId) {
        try {
            ResponseEntity<R<Map<String, Object>>> resp = restTemplate.exchange(
                    SERVICE_URL + "/model/" + modelId,
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<Map<String, Object>>>() {});
            R<Map<String, Object>> body = resp.getBody();
            if (body != null && body.getCode() == 0 && body.getData() != null) {
                return body.getData();
            }
        } catch (Exception e) {
            log.warn("调用集市服务查询模型详情失败: modelId={}, error={}", modelId, e.getMessage());
        }
        return Collections.emptyMap();
    }
}
