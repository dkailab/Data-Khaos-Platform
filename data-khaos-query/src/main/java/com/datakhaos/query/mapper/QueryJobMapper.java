package com.datakhaos.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.query.dto.QueryUsageDay;
import com.datakhaos.query.entity.QueryJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QueryJobMapper extends BaseMapper<QueryJob> {
    @Select("""
            SELECT CAST(create_time AS DATE) AS submitted_on,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS succeeded,
                   SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                   SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
                   SUM(CASE WHEN status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED') THEN 1 ELSE 0 END) AS active,
                   SUM(CASE WHEN status = 'SUCCESS' AND end_time >= start_time THEN 1 ELSE 0 END) AS timed_successes,
                   SUM(CASE WHEN status = 'SUCCESS' AND end_time >= start_time
                            THEN TIMESTAMPDIFF(SECOND, start_time, end_time) ELSE 0 END) AS execution_seconds
            FROM query_job
            WHERE user_id = #{owner} AND create_time >= #{from} AND create_time < #{until}
            GROUP BY CAST(create_time AS DATE)
            """)
    List<QueryUsageDay> summarizeUsage(@Param("owner") String owner,
                                       @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);
}
