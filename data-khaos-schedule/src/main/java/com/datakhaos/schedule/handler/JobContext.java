package com.datakhaos.schedule.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.entity.ScheduleJobLog;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务执行上下文：把「业务日期、幂等键、超时、参数解析」这些跨任务类型的规则收在一处。
 *
 * <p>业务日期与幂等键必须同时从「计划触发时刻」推导，否则重试或换实例后会漂移。</p>
 */
@Slf4j
public final class JobContext {

    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final ScheduleJob job;
    private final ScheduleJobLog jobLog;
    private final Map<String, Object> params;

    private JobContext(ScheduleJob job, ScheduleJobLog jobLog, Map<String, Object> params) {
        this.job = job;
        this.jobLog = jobLog;
        this.params = params;
    }

    public static JobContext of(ScheduleJob job, ScheduleJobLog jobLog) {
        return new JobContext(job, jobLog, parse(job.getParams()));
    }

    public ScheduleJob job() {
        return job;
    }

    public ScheduleJobLog log() {
        return jobLog;
    }

    public Map<String, Object> params() {
        return params;
    }

    /** 读取 params 中的字符串字段，缺失返回 null。 */
    public String param(String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 关联ID：refId 优先，回退 params 中指定字段。 */
    public String refOrParam(String key) {
        return StrUtil.isNotBlank(job.getRefId()) ? job.getRefId() : param(key);
    }

    public int timeoutSeconds(int defaultSeconds) {
        return job.getTimeout() == null || job.getTimeout() <= 0 ? defaultSeconds : job.getTimeout();
    }

    /**
     * 业务日期：调度触发按计划触发时刻（上海时区）的前一日，稳定可重放；
     * 手动触发取 params.bizDate，缺省取上海时区前一日。
     */
    public String bizDate() {
        LocalDateTime scheduledAt = jobLog.getScheduledAt();
        if (scheduledAt != null) {
            return scheduledAt.atZone(BIZ_ZONE).toLocalDate().minusDays(1).toString();
        }
        LocalDate date = parseDate(param("bizDate"));
        if (date == null) {
            date = LocalDate.now(BIZ_ZONE).minusDays(1);
        }
        return date.toString();
    }

    /**
     * 幂等键前缀：调度触发按计划触发时刻生成（稳定可重放），手动触发按执行日志 ID。
     * 需要 UUID 的接口由调用方再派生。
     */
    public String scheduleKey() {
        LocalDateTime scheduledAt = jobLog.getScheduledAt();
        if (scheduledAt != null) {
            return "sched:" + job.getId() + ":"
                    + scheduledAt.atZone(BIZ_ZONE).format(KEY_FORMAT);
        }
        return "manual:" + jobLog.getId();
    }

    private static LocalDate parseDate(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> parse(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            JSONObject object = JSONUtil.parseObj(json);
            return new LinkedHashMap<>(object);
        } catch (Exception e) {
            log.warn("解析任务 params 失败，按空参数处理: {}", json);
            return Map.of();
        }
    }
}
