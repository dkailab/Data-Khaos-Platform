package com.datakhaos.approval.api.service;

import com.datakhaos.approval.api.model.ApprovalEvidenceDto;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.model.R;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * 审批服务客户端：供发布门禁与调度提交前查询「目标版本是否已审批通过」。
 *
 * <p>查不到返回 {@link Optional#empty()}；审批服务不可用抛
 * {@link IllegalStateException}，让调用方按「无法确认」处理，不能静默放行。</p>
 */
public class ApprovalApiClient {

    public static final String SERVICE_URL = "http://data-khaos-approval/api/approval";
    public static final String TYPE_WAREHOUSE_API = "WAREHOUSE_API";
    public static final String TYPE_FLINK_JOB = "FLINK_JOB";

    private final RestTemplate restTemplate;

    public ApprovalApiClient(@Qualifier("orchestrationRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询指定目标版本最近一次通过的审批。
     *
     * @param applyType      申请类型
     * @param targetId       目标 ID
     * @param targetRevision 目标修订号；null 表示不区分版本
     */
    public Optional<ApprovalEvidenceDto> findApproved(String applyType, String targetId, Integer targetRevision) {
        String url = SERVICE_URL + "/apply/approved?applyType={applyType}&targetId={targetId}";
        Object[] vars;
        if (targetRevision == null) {
            vars = new Object[]{applyType, targetId};
        } else {
            url += "&targetRevision={targetRevision}";
            vars = new Object[]{applyType, targetId, targetRevision};
        }
        try {
            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(systemHeaders()),
                    new ParameterizedTypeReference<R<ApprovalEvidenceDto>>() {}, vars);
            R<ApprovalEvidenceDto> body = response.getBody();
            if (body == null || body.getCode() != 0) {
                throw new IllegalStateException("审批服务返回异常: "
                        + (body == null ? "无响应" : body.getMsg()));
            }
            return Optional.ofNullable(body.getData());
        } catch (RestClientException e) {
            throw new IllegalStateException("审批服务暂不可用，无法确认审批状态", e);
        }
    }

    private HttpHeaders systemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CommonConstants.HEADER_USER_ID, CommonConstants.DEFAULT_ADMIN_ID);
        headers.set(CommonConstants.HEADER_USERNAME, "system");
        headers.set(CommonConstants.HEADER_REAL_NAME, "调度/发布门禁");
        headers.set(CommonConstants.HEADER_ROLES, CommonConstants.SUPER_ADMIN);
        return headers;
    }
}
