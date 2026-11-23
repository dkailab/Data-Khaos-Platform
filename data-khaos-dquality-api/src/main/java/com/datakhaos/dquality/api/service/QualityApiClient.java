package com.datakhaos.dquality.api.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.model.R;
import com.datakhaos.dquality.api.model.DqSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 数据质量服务 REST 客户端（通过注册中心负载均衡调用 data-khaos-dquality）。
 * 供工作流 QUALITY 节点触发稽核并拿回结论（PASS/FAIL/ERROR），用于门禁。
 *
 * <p>调用采用系统管理员身份（内部接口，不经过网关鉴权）。</p>
 */
@Slf4j
public class QualityApiClient {

    public static final String SERVICE_URL = "http://data-khaos-dquality/api/dquality/internal";

    /** 默认读取超时（秒）：质量稽核通常较快，超时按节点 timeout 覆盖 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 900;

    private final RestTemplate restTemplate;

    public QualityApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 触发质量任务并返回快照（含结论）。
     *
     * @param taskId            质量任务ID
     * @param workflowRunId     来源工作流运行ID（可空）
     * @param workflowNodeRunId 来源工作流节点运行ID（可空）
     * @param bizDate           业务日期（可空）
     */
    public DqSnapshotDto audit(String taskId, String workflowRunId,
                               String workflowNodeRunId, String bizDate) {
        // 直接拼接 taskId：若用 UriComponentsBuilder 模板再 encode，{id} 会被转成 %7Bid%7D
        // 且不传 uriVariables 时不会展开，服务端会收到字面量 "{id}" 而报「任务不存在」。
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(SERVICE_URL + "/task/" + taskId + "/audit")
                .queryParam("triggerType", "WORKFLOW");
        if (StrUtil.isNotBlank(workflowRunId)) {
            builder.queryParam("runId", workflowRunId);
        }
        if (StrUtil.isNotBlank(workflowNodeRunId)) {
            builder.queryParam("nodeRunId", workflowNodeRunId);
        }
        if (StrUtil.isNotBlank(bizDate)) {
            builder.queryParam("bizDate", bizDate);
        }

        try {
            ResponseEntity<R<DqSnapshotDto>> resp = restTemplate.exchange(
                    builder.build().encode().toUri(),
                    HttpMethod.POST,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<DqSnapshotDto>>() {});
            R<DqSnapshotDto> body = resp.getBody();
            if (body == null || body.getCode() != 0) {
                throw new IllegalStateException("质量稽核失败: "
                        + (body == null ? "无响应" : body.getMsg()));
            }
            return body.getData();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("调用数据质量服务异常: taskId={}, error={}", taskId, e.getMessage());
            throw new IllegalStateException("调用数据质量服务异常: " + e.getMessage(), e);
        }
    }

    /**
     * 按ID查询快照（PR3：元数据聚合运行证据）。查不到或服务不可达返回 null。
     */
    public DqSnapshotDto getSnapshot(String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }
        try {
            ResponseEntity<R<DqSnapshotDto>> resp = restTemplate.exchange(
                    SERVICE_URL + "/snapshot/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<DqSnapshotDto>>() {},
                    id);
            R<DqSnapshotDto> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData();
            }
            log.warn("查询质量快照失败: id={}, msg={}", id, body == null ? "无响应" : body.getMsg());
        } catch (Exception e) {
            log.warn("查询质量快照异常: id={}, error={}", id, e.getMessage());
        }
        return null;
    }

    /**
     * 查询某表某业务日最近一次稽核快照（PR3：数据地图回查质量证据）。查不到返回 null。
     */
    public DqSnapshotDto findLatestSnapshot(String tableName, String bizDate, String conclusion) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(SERVICE_URL + "/snapshot/latest");
            if (StrUtil.isNotBlank(tableName)) {
                builder.queryParam("tableName", tableName);
            }
            if (StrUtil.isNotBlank(bizDate)) {
                builder.queryParam("bizDate", bizDate);
            }
            if (StrUtil.isNotBlank(conclusion)) {
                builder.queryParam("conclusion", conclusion);
            }
            ResponseEntity<R<DqSnapshotDto>> resp = restTemplate.exchange(
                    builder.build().encode().toUri(),
                    HttpMethod.GET,
                    new HttpEntity<>(null, systemHeaders()),
                    new ParameterizedTypeReference<R<DqSnapshotDto>>() {});
            R<DqSnapshotDto> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData();
            }
        } catch (Exception e) {
            log.warn("查询最近质量快照异常: tableName={}, bizDate={}, error={}", tableName, bizDate, e.getMessage());
        }
        return null;
    }

    private HttpHeaders systemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CommonConstants.HEADER_USER_ID, CommonConstants.DEFAULT_ADMIN_ID);
        headers.set(CommonConstants.HEADER_USERNAME, "system");
        headers.set(CommonConstants.HEADER_REAL_NAME, "工作流系统");
        headers.set(CommonConstants.HEADER_ROLES, CommonConstants.SUPER_ADMIN);
        return headers;
    }
}
