package com.datakhaos.schedule.handler;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SQL 任务：直接在目标数据源执行配置的 SQL。 */
@Component
@RequiredArgsConstructor
public class SqlJobHandler implements JobHandler {

    private final DatasourceApiClient datasourceApiClient;

    @Override
    public String type() {
        return "SQL";
    }

    @Override
    public int execute(JobContext context) {
        var job = context.job();
        if (StrUtil.isBlank(job.getDatasourceId()) || StrUtil.isBlank(job.getTargetSql())) {
            throw new BusinessException("SQL 任务未配置数据源或 SQL");
        }
        R<QueryResult> result = datasourceApiClient.executeRaw(job.getDatasourceId(), job.getTargetSql());
        if (result == null || result.getCode() != 0) {
            throw new BusinessException(result == null ? "SQL 执行失败" : result.getMsg());
        }
        QueryResult data = result.getData();
        return data == null || data.getRowCount() == null ? 0 : data.getRowCount();
    }
}
