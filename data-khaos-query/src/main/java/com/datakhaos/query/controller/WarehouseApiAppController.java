package com.datakhaos.query.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.query.entity.WarehouseApiApp;
import com.datakhaos.query.service.WarehouseApiAppService;
import com.datakhaos.query.service.WarehouseApiCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 数仓 API 应用注册与运营：应用、授权、应用凭据。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "数仓 API 应用")
public class WarehouseApiAppController {
    private static final String BASE = "/api/query/warehouse/v1/projects/{projectId}/apps";
    private static final String APP = BASE + "/{appId}";

    private final WarehouseApiAppService apps;
    private final WarehouseApiCredentialService credentials;

    @GetMapping(BASE)
    @Operation(summary = "分页查看项目应用")
    public R<PageResult<WarehouseApiApp>> list(@PathVariable String projectId,
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(apps.list(projectId, current, size));
    }

    @PostMapping(BASE)
    @Operation(summary = "注册应用")
    public R<WarehouseApiApp> create(@PathVariable String projectId, @RequestBody AppRequest request) {
        return R.ok(apps.create(projectId, request.name(), request.owner(), request.runtimeSubject()));
    }

    @GetMapping(APP)
    @Operation(summary = "查看应用详情")
    public R<WarehouseApiApp> detail(@PathVariable String projectId, @PathVariable String appId) {
        return R.ok(apps.detail(projectId, appId));
    }

    @PutMapping(APP)
    @Operation(summary = "编辑应用")
    public R<WarehouseApiApp> update(@PathVariable String projectId, @PathVariable String appId,
            @RequestParam int revision, @RequestBody AppRequest request) {
        return R.ok(apps.update(projectId, appId, revision, request.name(), request.owner(),
                request.runtimeSubject()));
    }

    @PostMapping(APP + "/status")
    @Operation(summary = "启用或停用应用")
    public R<WarehouseApiApp> status(@PathVariable String projectId, @PathVariable String appId,
            @RequestParam int revision, @RequestParam boolean enabled) {
        return R.ok(apps.setEnabled(projectId, appId, revision, enabled));
    }

    @GetMapping(APP + "/grants")
    @Operation(summary = "分页查看应用授权")
    public R<PageResult<WarehouseApiAppService.GrantView>> grants(@PathVariable String projectId,
            @PathVariable String appId, @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(apps.listGrants(projectId, appId, current, size));
    }

    @PostMapping(APP + "/grants")
    @Operation(summary = "按 API 版本授权")
    public R<WarehouseApiAppService.GrantView> grant(@PathVariable String projectId, @PathVariable String appId,
            @RequestBody GrantRequest request) {
        return R.ok(apps.grant(projectId, request.apiCode(), request.version(), appId,
                request.quotaPerMinute(), request.expiresAt()));
    }

    @PostMapping(APP + "/grants/{grantId}/revoke")
    @Operation(summary = "回收应用授权")
    public R<Void> revokeGrant(@PathVariable String projectId, @PathVariable String appId,
            @PathVariable String grantId) {
        apps.revokeGrant(projectId, appId, grantId);
        return R.ok();
    }

    @GetMapping(APP + "/credentials/{apiCode}/versions/{version}")
    @Operation(summary = "分页查看应用在该版本的凭据")
    public R<PageResult<WarehouseApiCredentialService.Credential>> credentials(@PathVariable String projectId,
            @PathVariable String appId, @PathVariable String apiCode, @PathVariable int version,
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(credentials.listForApp(projectId, apiCode, version, appId, current, size));
    }

    @PostMapping(APP + "/credentials/{apiCode}/versions/{version}")
    @Operation(summary = "为应用签发调用凭据")
    public R<WarehouseApiCredentialService.Issued> issue(@PathVariable String projectId,
            @PathVariable String appId, @PathVariable String apiCode, @PathVariable int version,
            @RequestBody TokenRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.ok(credentials.issueForApp(projectId, apiCode, version, appId, request.ttlHours()));
    }

    @PostMapping(APP + "/credentials/{apiCode}/versions/{version}/{credentialId}/revoke")
    @Operation(summary = "吊销应用调用凭据")
    public R<Void> revoke(@PathVariable String projectId, @PathVariable String appId, @PathVariable String apiCode,
            @PathVariable int version, @PathVariable String credentialId) {
        credentials.revokeForApp(projectId, apiCode, version, appId, credentialId);
        return R.ok();
    }

    public record AppRequest(String name, String owner, String runtimeSubject) {}

    public record GrantRequest(String apiCode, int version, int quotaPerMinute, Instant expiresAt) {}

    public record TokenRequest(int ttlHours) {}
}
