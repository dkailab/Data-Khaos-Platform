package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.AuthUser;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.security.WarehouseApiToken;
import com.datakhaos.query.dto.OrderRegionGmv;
import com.datakhaos.query.dto.RealtimeRegionGmv;
import com.datakhaos.query.entity.WarehouseApiApp;
import com.datakhaos.query.entity.WarehouseApiCallAudit;
import com.datakhaos.query.entity.WarehouseApiCredential;
import com.datakhaos.query.entity.WarehouseApiVersion;
import com.datakhaos.query.exception.QuotaExceededException;
import com.datakhaos.query.mapper.WarehouseApiCredentialMapper;
import com.datakhaos.query.mapper.WarehouseApiVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseApiCredentialService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BEARER = "Bearer ";
    private static final Pattern TOKEN = Pattern.compile(WarehouseApiToken.PREFIX + "[A-Za-z0-9_-]{43}");
    private static final int MAX_TTL_HOURS = 168;
    private static final long WINDOW_MS = 60_000L;

    private final WarehouseApiCredentialMapper mapper;
    private final WarehouseApiVersionMapper versions;
    private final WarehouseApiService apis;
    private final WarehouseApiAppService appService;
    private final WarehouseApiQuotaService quotas;
    private final WarehouseApiAuditService audits;

    /**
     * 签发人委派凭据（PRC 之前的形态）：以签发人身份执行，存量凭据保留至过期或吊销。
     */
    public Issued issue(String projectId, String apiCode, int version, String appName, int ttlHours) {
        String user = apis.requireProject(projectId, true, true);
        if (CommonConstants.DEFAULT_ADMIN_ID.equals(user)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请用具有项目和资产权限的非内置管理员账号签发");
        }
        var release = apis.requirePublished(projectId, apiCode, version);
        String name = requireAppName(appName);
        requireTtl(ttlHours);
        return issueCredential(release.getId(), null, name, user, ttlHours);
    }

    /**
     * 为应用签发调用凭据：归属到 appId，需该应用已获此 API 版本的有效授权。
     * 同一 {appId, apiVersionId} 可并存多枚凭据，用于不停机滚动轮换。
     */
    public Issued issueForApp(String projectId, String apiCode, int version, String appId, int ttlHours) {
        String user = apis.requireProject(projectId, true, true);
        if (CommonConstants.DEFAULT_ADMIN_ID.equals(user)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请用具有项目和资产权限的非内置管理员账号签发");
        }
        var release = apis.requirePublished(projectId, apiCode, version);
        var app = appService.requireGrantedApp(projectId, appId, release.getId());
        requireTtl(ttlHours);
        return issueCredential(release.getId(), app.getId(), app.getAppName(), user, ttlHours);
    }

    private Issued issueCredential(String apiVersionId, String appId, String appName, String user, int ttlHours) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = WarehouseApiToken.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var credential = new WarehouseApiCredential();
        var now = now();
        credential.setApiVersionId(apiVersionId);
        credential.setAppId(appId);
        credential.setAppName(appName);
        credential.setTokenDigest(digest(token));
        credential.setIssuedBy(user);
        credential.setIssuedAt(now);
        credential.setExpiresAt(now.plusHours(ttlHours));
        mapper.insert(credential);
        return new Issued(view(credential), token);
    }

    public PageResult<Credential> list(String projectId, String apiCode, int version, long current, long size) {
        apis.requireProject(projectId, true, true);
        var release = apis.find(projectId, apiCode, version);
        if (current < 1 || current > 10_000 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数超出范围");
        }
        return page(release.getId(), current, size);
    }

    /** 应用维度的凭据列表：该应用在此版本下的全部凭据，含已吊销。 */
    public PageResult<Credential> listForApp(String projectId, String apiCode, int version,
            String appId, long current, long size) {
        apis.requireProject(projectId, true, true);
        var release = apis.find(projectId, apiCode, version);
        var app = appService.find(projectId, appId);
        if (current < 1 || current > 10_000 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数超出范围");
        }
        var page = mapper.selectPage(new Page<>(current, size), Wrappers.<WarehouseApiCredential>lambdaQuery()
                .eq(WarehouseApiCredential::getApiVersionId, release.getId())
                .eq(WarehouseApiCredential::getAppId, app.getId())
                .orderByDesc(WarehouseApiCredential::getIssuedAt, WarehouseApiCredential::getId));
        return PageResult.of(current, size, page.getTotal(), page.getRecords().stream().map(this::view).toList());
    }

    private PageResult<Credential> page(String apiVersionId, long current, long size) {
        var page = mapper.selectPage(new Page<>(current, size), Wrappers.<WarehouseApiCredential>lambdaQuery()
                .eq(WarehouseApiCredential::getApiVersionId, apiVersionId)
                .orderByDesc(WarehouseApiCredential::getIssuedAt, WarehouseApiCredential::getId));
        return PageResult.of(current, size, page.getTotal(), page.getRecords().stream().map(this::view).toList());
    }

    public void revoke(String projectId, String apiCode, int version, String credentialId) {
        String user = apis.requireProject(projectId, true, true);
        var release = apis.find(projectId, apiCode, version);
        revokeCredential(release.getId(), null, credentialId, user);
    }

    public void revokeForApp(String projectId, String apiCode, int version, String appId, String credentialId) {
        String user = apis.requireProject(projectId, true, true);
        var release = apis.find(projectId, apiCode, version);
        var app = appService.find(projectId, appId);
        revokeCredential(release.getId(), app.getId(), credentialId, user);
    }

    private void revokeCredential(String apiVersionId, String appId, String credentialId, String user) {
        var credential = mapper.selectById(credentialId);
        if (credential == null || !apiVersionId.equals(credential.getApiVersionId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "调用凭据不存在");
        }
        if (appId != null && !appId.equals(credential.getAppId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "调用凭据不属于该应用");
        }
        mapper.update(null, Wrappers.<WarehouseApiCredential>lambdaUpdate()
                .eq(WarehouseApiCredential::getId, credentialId)
                .eq(WarehouseApiCredential::getApiVersionId, apiVersionId)
                .isNull(WarehouseApiCredential::getRevokedAt)
                .set(WarehouseApiCredential::getRevokedBy, user).set(WarehouseApiCredential::getRevokedAt, now()));
    }

    /**
     * 机器调用入口。
     *
     * <p>应用凭据先确认应用启停与授权有效期，再按 {@code appId + apiVersionId} 限流，全部通过后才执行查询；
     * 存量签发人委派凭据沿用签发人身份，不受配额约束。无论成功失败都落一条调用审计。
     */
    public Object invoke(String projectId, String apiCode, int version, String bizDate,
            String authorization, String requestId) {
        var credential = authenticate(authorization);
        var release = versions.selectById(credential.getApiVersionId());
        if (release == null || !release.getProjectId().equals(projectId) || !release.getApiCode().equals(apiCode)
                || release.getApiVersion() != version) {
            throw unauthorized();
        }

        var previous = MetadataHolder.get();
        long started = System.nanoTime();
        int code = ResultCode.SYSTEM_ERROR.getCode();
        String publicationId = null;
        String qualitySnapshotId = null;
        String freshness = null;
        try {
            String subject = credential.getIssuedBy();
            if (credential.getAppId() != null) {
                WarehouseApiApp app = appService.requireEnabled(projectId, credential.getAppId());
                var grant = appService.requireActiveGrant(app.getId(), release.getId());
                subject = app.getRuntimeSubject();
                MetadataHolder.set(AuthUser.builder().id(subject).build());
                apis.requireProject(projectId, false, true);
                if (release.getStatus() != WarehouseApiVersion.Status.PUBLISHED) {
                    throw new BusinessException(ResultCode.NOT_FOUND, "该 API 版本未发布或已下线");
                }
                if (!quotas.tryAcquire(app.getId(), release.getId(), grant.getQuotaPerMinute())) {
                    int retryAfter = (int) Math.max(1,
                            60 - (System.currentTimeMillis() % WINDOW_MS) / 1000);
                    throw new QuotaExceededException(
                            "应用在本分钟内的配额已用尽（" + grant.getQuotaPerMinute() + " 次/分钟）", retryAfter);
                }
            } else {
                MetadataHolder.set(AuthUser.builder().id(subject).build());
                apis.requireProject(projectId, true, true);
                if (release.getStatus() != WarehouseApiVersion.Status.PUBLISHED) {
                    throw new BusinessException(ResultCode.NOT_FOUND, "该 API 版本未发布或已下线");
                }
            }
            Object result = apis.execute(release, bizDate);
            if (result instanceof OrderRegionGmv batch) {
                publicationId = batch.publicationId();
                qualitySnapshotId = batch.qualitySnapshotId();
            } else if (result instanceof RealtimeRegionGmv realtime) {
                freshness = realtime.freshness();
            }
            code = ResultCode.SUCCESS.getCode();
            return result;
        } catch (BusinessException e) {
            code = e.getCode();
            throw e;
        } finally {
            if (previous == null) MetadataHolder.clear();
            else MetadataHolder.set(previous);
            audits.record(audit(requestId, projectId, credential, release, bizDate, code,
                    (System.nanoTime() - started) / 1_000_000, publicationId, qualitySnapshotId, freshness));
            log.info("数仓 API 调用 requestId={} credentialId={} appId={} projectId={} apiCode={} version={} code={} publicationId={} freshness={} elapsedMs={}",
                    requestId, credential.getId(), credential.getAppId(), projectId, apiCode, version, code,
                    publicationId, freshness, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private WarehouseApiCallAudit audit(String requestId, String projectId, WarehouseApiCredential credential,
            WarehouseApiVersion release, String bizDate, int code, long costMs,
            String publicationId, String qualitySnapshotId, String freshness) {
        var row = new WarehouseApiCallAudit();
        row.setRequestId(requestId);
        row.setProjectId(projectId);
        row.setAppId(credential.getAppId());
        row.setCredentialId(credential.getId());
        row.setApiVersionId(release.getId());
        row.setApiCode(release.getApiCode());
        row.setApiVersion(release.getApiVersion());
        row.setBizDate(bizDate);
        row.setResultCode(code);
        row.setCostMs(costMs);
        row.setPublicationId(publicationId);
        row.setQualitySnapshotId(qualitySnapshotId);
        row.setFreshness(freshness);
        row.setCalledAt(now());
        return row;
    }

    private WarehouseApiCredential authenticate(String authorization) {
        if (!WarehouseApiToken.isMachineAuthorization(authorization)) throw unauthorized();
        String token = authorization.substring(BEARER.length());
        if (!TOKEN.matcher(token).matches()) throw unauthorized();
        var credential = mapper.selectOne(Wrappers.<WarehouseApiCredential>lambdaQuery()
                .eq(WarehouseApiCredential::getTokenDigest, digest(token))
                .isNull(WarehouseApiCredential::getRevokedAt)
                .gt(WarehouseApiCredential::getExpiresAt, now()));
        if (credential == null) throw unauthorized();
        // 存量委派凭据仍受签发人可用性约束；应用凭据的身份由应用运行主体决定，不在此处判定。
        if (credential.getAppId() == null && CommonConstants.DEFAULT_ADMIN_ID.equals(credential.getIssuedBy())) {
            throw unauthorized();
        }
        return credential;
    }

    private Credential view(WarehouseApiCredential row) {
        return new Credential(row.getId(), row.getAppId(), row.getAppName(), row.getIssuedBy(),
                utc(row.getIssuedAt()), utc(row.getExpiresAt()), row.getRevokedBy(), utc(row.getRevokedAt()));
    }

    private static String requireAppName(String appName) {
        String name = appName == null ? "" : appName.strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "应用名称无效");
        }
        return name;
    }

    private static void requireTtl(int ttlHours) {
        if (ttlHours < 1 || ttlHours > MAX_TTL_HOURS) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "有效期需在 1 至 " + MAX_TTL_HOURS + " 小时之间");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ResultCode.UNAUTHORIZED, "调用凭据无效、过期、已吊销或与此版本不匹配");
    }

    public record Credential(String id, String appId, String appName, String issuedBy, Instant issuedAt,
                             Instant expiresAt, String revokedBy, Instant revokedAt) {}

    public record Issued(Credential credential, String token) {}
}
