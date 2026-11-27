package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.dto.WorkflowGraphRequest;
import com.datakhaos.workflow.dto.WorkflowPublishResult;
import com.datakhaos.workflow.dto.WorkflowRevisionView;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 发布管理：发布前自动固化版本快照并校验 DAG，发布后绑定版本号；下线回到停用态。
 *
 * <p>状态约定沿用存量：0=停用/下线，1=启用但未发布，2=已发布（仍参与调度）。
 * 发布绑定版本号后，运行实例可据此判断「跑的是哪一版」。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkflowPublishService {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_PUBLISHED = 2;

    private final WorkflowDefMapper defMapper;
    private final WorkflowService workflowService;
    private final WorkflowRevisionService revisionService;
    private final WorkflowProjectAccess access;

    @Transactional(rollbackFor = Exception.class)
    public WorkflowPublishResult publish(String wfId) {
        WorkflowDef definition = require(wfId);
        access.requireAction(definition.getProjectId(), "task:develop");
        access.requireAction(definition.getProjectId(), "task:schedule");

        WorkflowGraphRequest graph = workflowService.detail(wfId);
        WorkflowGraphValidator.validate(graph.getNodes(), graph.getEdges());
        String note = "发布快照 " + LocalDateTime.now().withNano(0).toString().replace('T', ' ');
        WorkflowRevisionView revision = revisionService.create(wfId, note);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        definition.setStatus(STATUS_PUBLISHED);
        definition.setPublishedRevision(revision.revisionNumber());
        definition.setPublishedAt(now);
        definition.setUpdateTime(now);
        defMapper.updateById(definition);
        return new WorkflowPublishResult(wfId, STATUS_PUBLISHED, revision.revisionNumber(), now);
    }

    /** 下线：停用调度，保留历史版本与发布记录。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowPublishResult offline(String wfId) {
        WorkflowDef definition = require(wfId);
        access.requireAction(definition.getProjectId(), "task:schedule");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int revision = definition.getPublishedRevision() == null ? 0 : definition.getPublishedRevision();
        // publishedAt 需要显式置空：updateById 默认跳过 null 字段，会把旧发布时间留在库里
        defMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WorkflowDef>()
                .eq(WorkflowDef::getId, wfId)
                .set(WorkflowDef::getStatus, STATUS_DISABLED)
                .set(WorkflowDef::getPublishedAt, null)
                .set(WorkflowDef::getUpdateTime, now));
        return new WorkflowPublishResult(wfId, STATUS_DISABLED, revision, null);
    }

    private WorkflowDef require(String wfId) {
        WorkflowDef definition = wfId == null ? null : defMapper.selectById(wfId);
        if (definition == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务流不存在或已删除");
        }
        return definition;
    }
}
