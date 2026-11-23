package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.security.AuthUser;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.query.dto.QueryExecuteRequest;
import com.datakhaos.query.entity.QueryJob;
import com.datakhaos.query.entity.QueryJobStatus;
import com.datakhaos.query.mapper.QueryJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static com.datakhaos.query.entity.QueryJobStatus.*;

@Slf4j
@Component
public class QueryJobWorker {
    private final QueryJobMapper mapper;
    private final QueryService queries;
    private final ObjectMapper json;
    private final ThreadPoolTaskExecutor executor;
    private final DatasourceApiClient datasource;
    private final PermissionApiClient permissions;

    public QueryJobWorker(QueryJobMapper mapper, QueryService queries, ObjectMapper json,
                          @Qualifier("queryJobExecutor") ThreadPoolTaskExecutor executor,
                          @Qualifier("queryJobRestTemplate") RestTemplate restTemplate) {
        this.mapper = mapper;
        this.queries = queries;
        this.json = json;
        this.executor = executor;
        this.datasource = new DatasourceApiClient(restTemplate);
        this.permissions = new PermissionApiClient(restTemplate);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        int slots = executor.getMaxPoolSize() - executor.getActiveCount();
        if (slots <= 0) {
            return;
        }
        var candidates = mapper.selectPage(new Page<>(1, slots, false),
                Wrappers.<QueryJob>lambdaQuery()
                        .select(QueryJob::getId)
                        .eq(QueryJob::getStatus, QUEUED)
                        .ge(QueryJob::getCreateTime, LocalDateTime.now().minusMinutes(15))
                        .orderByAsc(QueryJob::getCreateTime, QueryJob::getId));
        for (QueryJob candidate : candidates.getRecords()) {
            try {
                executor.execute(() -> run(candidate.getId()));
            } catch (TaskRejectedException e) {
                // 任务仍在数据库排队，下次扫描会再次尝试。
                return;
            }
        }
    }

    void run(String id) {
        int claimed = mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getId, id).eq(QueryJob::getStatus, QUEUED)
                .set(QueryJob::getStatus, RUNNING).set(QueryJob::getStartTime, LocalDateTime.now()));
        if (claimed == 0) {
            return;
        }

        try {
            QueryJob job = mapper.selectById(id);
            if (job.getStatus() == CANCEL_REQUESTED) {
                finishCancelled(id);
                return;
            }
            if (job.getStatus() != RUNNING) {
                return;
            }
            MetadataHolder.set(AuthUser.builder().id(job.getUserId()).build());
            QueryExecuteRequest request = new QueryExecuteRequest();
            request.setDatasourceId(job.getDatasourceId());
            request.setDatabaseName(job.getDatabaseName());
            request.setSql(job.getSqlText());
            QueryResult result = queries.execute(request, datasource, permissions);
            if (result == null || Boolean.TRUE.equals(result.getUpdate())) {
                throw new BusinessException("数据源没有返回可留存的查询结果");
            }

            String payload = json.writeValueAsString(result);
            if (payload.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
                throw new BusinessException("查询结果超过 1 MiB，请缩小查询范围后重试");
            }
            finish(id, SUCCESS, payload, result.getRowCount(), null);
        } catch (BusinessException e) {
            finish(id, FAILED, null, null, e.getMessage());
        } catch (Exception e) {
            log.warn("查询任务执行失败 id={}", id, e);
            finish(id, FAILED, null, null, "查询任务执行失败");
        } finally {
            MetadataHolder.clear();
        }
    }

    private void finish(String id, QueryJobStatus status, String payload, Integer rows, String error) {
        LocalDateTime now = LocalDateTime.now();
        int updated = mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getId, id).eq(QueryJob::getStatus, RUNNING)
                .set(QueryJob::getStatus, status).set(QueryJob::getEndTime, now)
                .set(QueryJob::getResultJson, payload).set(QueryJob::getRowCount, rows)
                .set(QueryJob::getResultExpiresAt, payload == null ? null : now.plusHours(24))
                .set(QueryJob::getErrorMessage, error == null ? null : error.substring(0, Math.min(500, error.length()))));
        if (updated == 0) {
            finishCancelled(id);
        }
    }

    private void finishCancelled(String id) {
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getId, id).eq(QueryJob::getStatus, CANCEL_REQUESTED)
                .set(QueryJob::getStatus, CANCELLED).set(QueryJob::getEndTime, LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getStatus, QUEUED).lt(QueryJob::getCreateTime, now.minusMinutes(15))
                .set(QueryJob::getStatus, FAILED).set(QueryJob::getEndTime, now)
                .set(QueryJob::getErrorMessage, "等待执行超时，请重新提交"));
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getStatus, RUNNING).lt(QueryJob::getStartTime, now.minusMinutes(10))
                .set(QueryJob::getStatus, FAILED).set(QueryJob::getEndTime, now)
                .set(QueryJob::getErrorMessage, "执行超时或服务中断，请重新提交"));
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getStatus, CANCEL_REQUESTED).lt(QueryJob::getStartTime, now.minusMinutes(10))
                .set(QueryJob::getStatus, CANCELLED).set(QueryJob::getEndTime, now));
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .lt(QueryJob::getResultExpiresAt, now).isNotNull(QueryJob::getResultJson)
                .set(QueryJob::getResultJson, null));
        mapper.delete(Wrappers.<QueryJob>lambdaQuery().lt(QueryJob::getEndTime, now.minusDays(30)));
    }
}
