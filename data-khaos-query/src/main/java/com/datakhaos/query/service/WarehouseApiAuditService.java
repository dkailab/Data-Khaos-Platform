package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.query.entity.WarehouseApiCallAudit;
import com.datakhaos.query.mapper.WarehouseApiCallAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 调用审计：只落调用元数据，凭据与原始业务结果不进审计表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseApiAuditService {
    private final WarehouseApiCallAuditMapper mapper;

    /** 审计写入失败不能让调用失败，否则会因审计存储抖动造成业务不可用。 */
    public void record(WarehouseApiCallAudit audit) {
        try {
            mapper.insert(audit);
        } catch (RuntimeException e) {
            log.warn("写入数仓 API 调用审计失败 requestId={} code={}: {}",
                    audit.getRequestId(), audit.getResultCode(), e.getMessage());
        }
    }

    /**
     * 按项目与日期分页查询调用记录。
     *
     * @param from 起始日期（含），空表示不限
     * @param to   结束日期（含），空表示不限
     */
    public PageResult<View> page(String projectId, String appId, String requestId,
            String from, String to, long current, long size) {
        if (current < 1 || current > 10_000 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数超出范围");
        }
        var query = Wrappers.<WarehouseApiCallAudit>lambdaQuery()
                .eq(WarehouseApiCallAudit::getProjectId, projectId);
        if (appId != null && !appId.isBlank()) {
            query.eq(WarehouseApiCallAudit::getAppId, appId.strip());
        }
        if (requestId != null && !requestId.isBlank()) {
            query.eq(WarehouseApiCallAudit::getRequestId, requestId.strip());
        }
        LocalDate start = parseDate(from, "起始日期");
        LocalDate end = parseDate(to, "结束日期");
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "结束日期不能早于起始日期");
        }
        if (start != null) query.ge(WarehouseApiCallAudit::getCalledAt, start.atStartOfDay());
        if (end != null) query.lt(WarehouseApiCallAudit::getCalledAt, end.plusDays(1).atStartOfDay());
        query.orderByDesc(WarehouseApiCallAudit::getCalledAt, WarehouseApiCallAudit::getId);

        var page = mapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(current, size, page.getTotal(), page.getRecords().stream().map(View::of).toList());
    }

    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, label + "格式应为 yyyy-MM-dd");
        }
    }

    public record View(String requestId, String projectId, String appId, String credentialId,
                       String apiCode, Integer apiVersion, String apiVersionId, String bizDate,
                       int resultCode, long costMs, String publicationId, String qualitySnapshotId,
                       Instant calledAt) {
        static View of(WarehouseApiCallAudit row) {
            return new View(row.getRequestId(), row.getProjectId(), row.getAppId(), row.getCredentialId(),
                    row.getApiCode(), row.getApiVersion(), row.getApiVersionId(), row.getBizDate(),
                    row.getResultCode(), row.getCostMs(), row.getPublicationId(), row.getQualitySnapshotId(),
                    utc(row.getCalledAt()));
        }

        private static Instant utc(LocalDateTime value) {
            return value == null ? null : value.toInstant(ZoneOffset.UTC);
        }
    }
}
