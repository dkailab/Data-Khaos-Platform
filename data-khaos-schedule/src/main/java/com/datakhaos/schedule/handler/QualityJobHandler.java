package com.datakhaos.schedule.handler;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.schedule.client.DQualityApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 质量稽核任务：params.taskId 指向质量任务。 */
@Component
@RequiredArgsConstructor
public class QualityJobHandler implements JobHandler {

    private final DQualityApiClient dQualityApiClient;

    @Override
    public String type() {
        return "QUALITY";
    }

    @Override
    public int execute(JobContext context) {
        String taskId = context.param("taskId");
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException("QUALITY 任务未配置质量任务ID（params.taskId）");
        }
        R<Void> result = dQualityApiClient.runTask(taskId, "SCHEDULE");
        if (result == null || result.getCode() != 0) {
            throw new BusinessException(result == null ? "质量稽核触发失败" : result.getMsg());
        }
        return 1;
    }
}
