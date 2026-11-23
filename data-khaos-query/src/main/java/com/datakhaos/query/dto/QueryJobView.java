package com.datakhaos.query.dto;

import com.datakhaos.query.entity.QueryJob;
import com.datakhaos.query.entity.QueryJobStatus;

import java.time.LocalDateTime;

public record QueryJobView(String id, String datasourceId, String databaseName, String sqlText,
                           QueryJobStatus status, LocalDateTime createTime, LocalDateTime startTime,
                           LocalDateTime endTime, LocalDateTime resultExpiresAt, boolean resultAvailable,
                           Integer rowCount, String errorMessage) {
    public static QueryJobView of(QueryJob job, LocalDateTime now) {
        return new QueryJobView(job.getId(), job.getDatasourceId(), job.getDatabaseName(), job.getSqlText(),
                job.getStatus(), job.getCreateTime(), job.getStartTime(), job.getEndTime(), job.getResultExpiresAt(),
                job.getResultExpiresAt() != null && job.getResultExpiresAt().isAfter(now),
                job.getRowCount(), job.getErrorMessage());
    }
}
