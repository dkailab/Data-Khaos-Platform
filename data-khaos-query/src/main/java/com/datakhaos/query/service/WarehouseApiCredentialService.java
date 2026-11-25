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
import com.datakhaos.query.entity.WarehouseApiCredential;
import com.datakhaos.query.entity.WarehouseApiVersion;
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

    private final WarehouseApiCredentialMapper mapper;
    private final WarehouseApiVersionMapper versions;
    private final WarehouseApiService apis;
    private final MaterializationQueryService materializations;

    public Issued issue(String projectId, String apiCode, int version, String appName, int ttlHours) {
        String user = apis.requireProject(projectId, true, true);
        if (CommonConstants.DEFAULT_ADMIN_ID.equals(user)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请用具有项目和资产权限的非内置管理员账号签发");
        }
        var release = apis.requirePublished(projectId, apiCode, version);
        String name = appName == null ? "" : appName.strip();
        if (name.isEmpty() || name.length() > 100 || ttlHours < 1 || ttlHours > MAX_TTL_HOURS) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "应用名称无效，或有效期超出 1 至 168 小时");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = WarehouseApiToken.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var credential = new WarehouseApiCredential();
        var now = now();
        credential.setApiVersionId(release.getId());
        credential.setAppName(name);
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
        var page = mapper.selectPage(new Page<>(current, size), Wrappers.<WarehouseApiCredential>lambdaQuery()
                .eq(WarehouseApiCredential::getApiVersionId, release.getId())
                .orderByDesc(WarehouseApiCredential::getIssuedAt, WarehouseApiCredential::getId));
        return PageResult.of(current, size, page.getTotal(), page.getRecords().stream().map(this::view).toList());
    }

    public void revoke(String projectId, String apiCode, int version, String credentialId) {
        String user = apis.requireProject(projectId, true, true);
        var release = apis.find(projectId, apiCode, version);
        var credential = mapper.selectById(credentialId);
        if (credential == null || !release.getId().equals(credential.getApiVersionId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "调用凭据不存在");
        }
        mapper.update(null, Wrappers.<WarehouseApiCredential>lambdaUpdate()
                .eq(WarehouseApiCredential::getId, credentialId)
                .eq(WarehouseApiCredential::getApiVersionId, release.getId())
                .isNull(WarehouseApiCredential::getRevokedAt)
                .set(WarehouseApiCredential::getRevokedBy, user).set(WarehouseApiCredential::getRevokedAt, now()));
    }

    public OrderRegionGmv invoke(String projectId, String apiCode, int version, String bizDate, String authorization) {
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
        try {
            MetadataHolder.set(AuthUser.builder().id(credential.getIssuedBy()).build());
            apis.requireProject(projectId, true, true);
            if (release.getStatus() != WarehouseApiVersion.Status.PUBLISHED) {
                throw new BusinessException(ResultCode.NOT_FOUND, "该 API 版本未发布或已下线");
            }
            var result = materializations.orderRegionGmv(bizDate, release.getMaxRows());
            publicationId = result.publicationId();
            code = ResultCode.SUCCESS.getCode();
            return result;
        } catch (BusinessException e) {
            code = e.getCode();
            throw e;
        } finally {
            if (previous == null) MetadataHolder.clear();
            else MetadataHolder.set(previous);
            log.info("数仓 API 调用 credentialId={} projectId={} apiCode={} version={} code={} publicationId={} elapsedMs={}",
                    credential.getId(), projectId, apiCode, version, code, publicationId, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private WarehouseApiCredential authenticate(String authorization) {
        if (!WarehouseApiToken.isMachineAuthorization(authorization)) throw unauthorized();
        String token = authorization.substring(BEARER.length());
        if (!TOKEN.matcher(token).matches()) throw unauthorized();
        var credential = mapper.selectOne(Wrappers.<WarehouseApiCredential>lambdaQuery()
                .eq(WarehouseApiCredential::getTokenDigest, digest(token))
                .isNull(WarehouseApiCredential::getRevokedAt)
                .gt(WarehouseApiCredential::getExpiresAt, now()));
        if (credential == null || CommonConstants.DEFAULT_ADMIN_ID.equals(credential.getIssuedBy())) throw unauthorized();
        return credential;
    }

    private Credential view(WarehouseApiCredential row) {
        return new Credential(row.getId(), row.getAppName(), row.getIssuedBy(), utc(row.getIssuedAt()),
                utc(row.getExpiresAt()), row.getRevokedBy(), utc(row.getRevokedAt()));
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

    public record Credential(String id, String appName, String issuedBy, Instant issuedAt, Instant expiresAt,
                             String revokedBy, Instant revokedAt) {}

    public record Issued(Credential credential, String token) {}
}
