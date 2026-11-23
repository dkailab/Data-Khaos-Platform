package com.datakhaos.query.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.security.SqlAuditUtil;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.QueryJobRequest;
import com.datakhaos.query.dto.QueryJobView;
import com.datakhaos.query.dto.QueryUsageDay;
import com.datakhaos.query.entity.QueryJob;
import com.datakhaos.query.entity.QueryJobStatus;
import com.datakhaos.query.mapper.QueryJobMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.datakhaos.query.entity.QueryJobStatus.*;

@Service
@RequiredArgsConstructor
public class QueryJobService {
    private final QueryJobMapper mapper;
    private final ObjectMapper json;

    public QueryJobView submit(QueryJobRequest request) {
        String owner = requireOwner();
        String requestId = normalizeRequestId(request.getRequestId());
        String datasource = StrUtil.trim(request.getDatasourceId());
        String database = StrUtil.trimToNull(request.getDatabaseName());
        if (StrUtil.isBlank(datasource) || datasource.length() > 32
                || (database != null && database.length() > 200)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "数据源或数据库参数无效");
        }
        String sql = validateSql(request.getSql());
        QueryJob existing = findRequest(owner, requestId);
        if (existing != null) {
            return repeatedRequest(existing, datasource, database, sql);
        }
        QueryJob job = new QueryJob();
        job.setUserId(owner);
        job.setRequestId(requestId);
        job.setDatasourceId(datasource);
        job.setDatabaseName(database);
        job.setSqlText(sql);
        job.setStatus(QUEUED);
        job.setCreateTime(LocalDateTime.now());
        try {
            mapper.insert(job);
        } catch (DuplicateKeyException e) {
            existing = findRequest(owner, requestId);
            if (existing == null) {
                throw e;
            }
            return repeatedRequest(existing, datasource, database, sql);
        }
        return view(job);
    }

    public PageResult<QueryJobView> page(long current, long size, QueryJobStatus status, LocalDate submittedOn) {
        String owner = requireOwner();
        if (current < 1 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "页码需大于 0，每页限制为 1 至 100 条");
        }
        var filters = Wrappers.<QueryJob>lambdaQuery().eq(QueryJob::getUserId, owner)
                .eq(status != null, QueryJob::getStatus, status);
        if (submittedOn != null) {
            filters.ge(QueryJob::getCreateTime, submittedOn.atStartOfDay())
                    .lt(QueryJob::getCreateTime, submittedOn.plusDays(1).atStartOfDay());
        }
        Page<QueryJob> page = mapper.selectPage(new Page<>(current, size),
                filters.orderByDesc(QueryJob::getCreateTime, QueryJob::getId));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::view).toList());
    }

    public List<QueryUsageDay> usage(int days) {
        String owner = requireOwner();
        if (days < 1 || days > 30) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "用量统计范围须为 1 至 30 天");
        }
        LocalDate until = LocalDate.now().plusDays(1);
        LocalDate from = until.minusDays(days);
        Map<LocalDate, QueryUsageDay> usage = mapper.summarizeUsage(owner, from.atStartOfDay(), until.atStartOfDay())
                .stream().collect(Collectors.toMap(QueryUsageDay::submittedOn, Function.identity()));
        return from.datesUntil(until)
                .map(day -> usage.getOrDefault(day, QueryUsageDay.empty(day)))
                .toList();
    }

    public QueryJobView detail(String id) {
        return view(findOwned(id, requireOwner()));
    }

    public QueryResult result(String id) {
        String owner = requireOwner();
        QueryJob job = findOwned(id, owner);
        LocalDateTime now = LocalDateTime.now();
        if (job.getStatus() != SUCCESS) {
            throw new BusinessException("该任务没有可读取的查询结果");
        }
        QueryJob stored = mapper.selectOne(Wrappers.<QueryJob>lambdaQuery()
                .select(QueryJob::getResultJson)
                .eq(QueryJob::getId, id).eq(QueryJob::getUserId, owner)
                .gt(QueryJob::getResultExpiresAt, now));
        if (stored == null || stored.getResultJson() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "结果已过期，请重新提交查询");
        }
        try {
            return json.readValue(stored.getResultJson(), QueryResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取查询任务结果 " + id, e);
        }
    }

    public QueryJobView cancel(String id) {
        String owner = requireOwner();
        findOwned(id, owner);
        LocalDateTime now = LocalDateTime.now();
        int removed = mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                .eq(QueryJob::getId, id).eq(QueryJob::getUserId, owner)
                .eq(QueryJob::getStatus, QUEUED)
                .set(QueryJob::getStatus, CANCELLED).set(QueryJob::getEndTime, now));
        if (removed == 0) {
            mapper.update(null, Wrappers.<QueryJob>lambdaUpdate()
                    .eq(QueryJob::getId, id).eq(QueryJob::getUserId, owner)
                    .eq(QueryJob::getStatus, RUNNING).set(QueryJob::getStatus, CANCEL_REQUESTED));
        }
        return view(findOwned(id, owner));
    }

    private QueryJob findOwned(String id, String owner) {
        QueryJob job = mapper.selectOne(Wrappers.<QueryJob>lambdaQuery()
                .eq(QueryJob::getId, id).eq(QueryJob::getUserId, owner));
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "查询任务不存在");
        }
        return job;
    }

    private QueryJob findRequest(String owner, String requestId) {
        return mapper.selectOne(Wrappers.<QueryJob>lambdaQuery()
                .eq(QueryJob::getUserId, owner).eq(QueryJob::getRequestId, requestId));
    }

    private QueryJobView repeatedRequest(QueryJob job, String datasource, String database, String sql) {
        if (!Objects.equals(job.getDatasourceId(), datasource)
                || !Objects.equals(job.getDatabaseName(), database) || !Objects.equals(job.getSqlText(), sql)) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "请求标识已用于不同的查询");
        }
        return view(job);
    }

    private static String normalizeRequestId(String requestId) {
        try {
            if (requestId != null && requestId.length() == 36) {
                return UUID.fromString(requestId).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // 统一返回入参校验错误。
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "请求标识必须为 UUID");
    }

    private static String validateSql(String input) {
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 16_384) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "SQL 必填且最多 16 KiB");
        }
        String sql = SqlAuditUtil.audit(input);
        try {
            if (CCJSqlParserUtil.parse(sql) instanceof Select select
                    && select.getSelectBody() instanceof PlainSelect plain
                    && (plain.getIntoTables() == null || plain.getIntoTables().isEmpty()) && !plain.isForUpdate()) {
                return sql;
            }
        } catch (JSQLParserException ignored) {
            // 使用统一的查询任务 SQL 校验错误。
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "查询任务只接受可解析的单条普通 SELECT");
    }

    private static String requireOwner() {
        String id = MetadataHolder.getUserId();
        if (StrUtil.isBlank(id)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return id;
    }

    private QueryJobView view(QueryJob job) {
        return QueryJobView.of(job, LocalDateTime.now());
    }
}
