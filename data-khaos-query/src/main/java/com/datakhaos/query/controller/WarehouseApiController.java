package com.datakhaos.query.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.WarehouseApiToken;
import com.datakhaos.query.dto.RealtimeReconcile;
import com.datakhaos.query.dto.WarehouseApiDraft;
import com.datakhaos.query.entity.WarehouseApiVersion;
import com.datakhaos.query.exception.QuotaExceededException;
import com.datakhaos.query.service.WarehouseApiCredentialService;
import com.datakhaos.query.service.WarehouseApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
@Tag(name = "数仓 API 发布")
public class WarehouseApiController {
    private static final String MANAGEMENT = "/api/query/warehouse/v1/projects/{projectId}/apis";
    private static final String VERSION = "/{apiCode}/versions/{version}";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final Pattern REQUEST_ID_VALUE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final WarehouseApiService apis;
    private final WarehouseApiCredentialService credentials;

    @GetMapping(MANAGEMENT)
    @Operation(summary = "分页查看项目 API 版本")
    public R<PageResult<WarehouseApiVersion>> list(@PathVariable String projectId,
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(apis.list(projectId, current, size));
    }

    @PostMapping(MANAGEMENT + VERSION)
    @Operation(summary = "创建 API 草稿（默认为离线分区契约）")
    public R<WarehouseApiVersion> create(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestBody WarehouseApiDraft draft) {
        return R.ok(apis.create(projectId, apiCode, version, draft));
    }

    @PutMapping(MANAGEMENT + VERSION)
    @Operation(summary = "编辑指定修订号的草稿")
    public R<WarehouseApiVersion> edit(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestParam int revision, @RequestBody WarehouseApiDraft draft) {
        return R.ok(apis.edit(projectId, apiCode, version, revision, draft));
    }

    @PostMapping(MANAGEMENT + VERSION + "/publish")
    @Operation(summary = "发布指定修订号的 API")
    public R<WarehouseApiVersion> publish(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestParam int revision) {
        return R.ok(apis.publish(projectId, apiCode, version, revision));
    }

    @PostMapping(MANAGEMENT + VERSION + "/offline")
    @Operation(summary = "下线已发布的 API 版本")
    public R<WarehouseApiVersion> offline(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestParam int revision) {
        return R.ok(apis.offline(projectId, apiCode, version, revision));
    }

    @PostMapping(MANAGEMENT + VERSION + "/credentials")
    @Operation(summary = "为已发布版本签发签发人委派凭据")
    public R<WarehouseApiCredentialService.Issued> issue(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestBody CredentialRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(credentials.issue(projectId, apiCode, version, request.appName(), request.ttlHours()));
    }

    @GetMapping(MANAGEMENT + VERSION + "/credentials")
    @Operation(summary = "分页查看凭据签发和吊销记录")
    public R<PageResult<WarehouseApiCredentialService.Credential>> credentials(@PathVariable String projectId,
            @PathVariable String apiCode, @PathVariable int version, @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(credentials.list(projectId, apiCode, version, current, size));
    }

    @PostMapping(MANAGEMENT + VERSION + "/credentials/{credentialId}/revoke")
    @Operation(summary = "吊销指定版本的调用凭据")
    public R<Void> revoke(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @PathVariable String credentialId) {
        credentials.revoke(projectId, apiCode, version, credentialId);
        return R.ok();
    }

    @GetMapping(WarehouseApiToken.DATA_ROUTE)
    @Operation(summary = "按业务日期调用已发布 API（离线返回发布证据，实时返回观测时间与时效）")
    public ResponseEntity<R<Object>> invoke(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestParam String bizDate,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = REQUEST_ID, required = false) String incomingRequestId,
            HttpServletResponse response) {
        String requestId = resolveRequestId(incomingRequestId);
        response.setHeader(REQUEST_ID, requestId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!WarehouseApiToken.isMachineAuthorization(authorization)) {
            return ResponseEntity.ok(R.<Object>ok(apis.invoke(projectId, apiCode, version, bizDate)));
        }
        try {
            return ResponseEntity.ok(R.<Object>ok(
                    credentials.invoke(projectId, apiCode, version, bizDate, authorization, requestId)));
        } catch (QuotaExceededException e) {
            // 配额超额是唯一需要改变 HTTP 状态码的业务异常：调用方据此退避重试。
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                    .body(R.fail(ResultCode.TOO_MANY_REQUESTS.getCode(), e.getMessage()));
        }
    }

    @GetMapping(MANAGEMENT + VERSION + "/reconcile")
    @Operation(summary = "实时与批 ADS 逐地区对账")
    public R<RealtimeReconcile> reconcile(@PathVariable String projectId, @PathVariable String apiCode,
            @PathVariable int version, @RequestParam String bizDate, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(apis.reconcile(projectId, apiCode, version, bizDate));
    }

    /** 接受调用方透传的关联 ID（便于跨系统串联），不合法则自行生成，避免脏值落审计。 */
    private static String resolveRequestId(String incoming) {
        if (incoming != null && REQUEST_ID_VALUE.matcher(incoming.strip()).matches()) {
            return incoming.strip();
        }
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record CredentialRequest(String appName, int ttlHours) {}
}
