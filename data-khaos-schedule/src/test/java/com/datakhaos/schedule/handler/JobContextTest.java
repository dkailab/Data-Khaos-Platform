package com.datakhaos.schedule.handler;

import com.datakhaos.schedule.entity.ScheduleJob;
import com.datakhaos.schedule.entity.ScheduleJobLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 业务日期与幂等键必须只由「计划触发时刻」决定，否则重试/换实例会漂移。 */
class JobContextTest {

    private static ScheduleJob job(String params) {
        ScheduleJob job = new ScheduleJob();
        job.setId("job-1");
        job.setJobName("orders");
        job.setJobType("WORKFLOW");
        job.setParams(params);
        return job;
    }

    private static ScheduleJobLog log(String id, LocalDateTime scheduledAt) {
        ScheduleJobLog log = new ScheduleJobLog();
        log.setId(id);
        log.setScheduledAt(scheduledAt);
        return log;
    }

    @Test
    void derivesBizDateAndKeyFromScheduledFireTime() {
        var context = JobContext.of(job(null), log("log-1", LocalDateTime.of(2026, 11, 27, 2, 0)));

        assertEquals("2026-11-26", context.bizDate());
        assertEquals("sched:job-1:202611270200", context.scheduleKey());
    }

    @Test
    void manualRunUsesParamsBizDate() {
        var context = JobContext.of(job("{\"bizDate\":\"2026-11-01\"}"), log("log-2", null));

        assertEquals("2026-11-01", context.bizDate());
        assertEquals("manual:log-2", context.scheduleKey());
    }

    @Test
    void refIdWinsOverParams() {
        ScheduleJob job = job("{\"workflowId\":\"from-params\"}");
        job.setRefId("from-ref");

        assertEquals("from-ref", JobContext.of(job, log("log-3", null)).refOrParam("workflowId"));
    }

    @Test
    void timeoutFallsBackToDefault() {
        ScheduleJob job = job(null);
        assertEquals(3600, JobContext.of(job, log("log-4", null)).timeoutSeconds(3600));

        job.setTimeout(120);
        assertEquals(120, JobContext.of(job, log("log-5", null)).timeoutSeconds(3600));
    }
}
