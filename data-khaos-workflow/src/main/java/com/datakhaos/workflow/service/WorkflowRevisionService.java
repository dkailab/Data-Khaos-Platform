package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.dto.WorkflowGraphRequest;
import com.datakhaos.workflow.dto.WorkflowRevisionView;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowRevision;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowRevisionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowRevisionService {
    private static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;

    private final WorkflowDefMapper definitions;
    private final WorkflowRevisionMapper revisions;
    private final WorkflowService workflows;
    private final WorkflowProjectAccess access;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public WorkflowRevisionView create(String wfId, String note) {
        WorkflowDef definition = requireWorkflow(wfId, true);
        String normalizedNote = requireText(note, 500, "版本说明");
        WorkflowGraphRequest graph = workflows.detail(wfId);
        GraphSnapshot snapshot = new GraphSnapshot(definition.getParams(), graph.getNodes(), graph.getEdges());
        String serialized;
        try {
            serialized = json.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成工作流版本", e);
        }
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "工作流版本内容超过 1 MiB");
        }
        Integer previous = revisions.latestNumber(wfId);
        WorkflowRevision revision = new WorkflowRevision();
        revision.setWfId(wfId);
        revision.setProjectId(StrUtil.trimToNull(definition.getProjectId()));
        revision.setRevisionNumber(previous == null ? 1 : previous + 1);
        revision.setNote(normalizedNote);
        revision.setCreatedBy(MetadataHolder.getUserId());
        revision.setCreateTime(LocalDateTime.now());
        revision.setNodeCount(snapshot.nodes().size());
        revision.setEdgeCount(snapshot.edges().size());
        revision.setGraphJson(serialized);
        revisions.insert(revision);
        return WorkflowRevisionView.of(revision);
    }

    public PageResult<WorkflowRevisionView> page(String wfId, long current, long size) {
        WorkflowDef definition = requireWorkflow(wfId, false);
        if (current < 1 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "页码需大于 0，每页须为 1 至 100 条");
        }
        Page<WorkflowRevision> page = revisions.selectPage(new Page<>(current, size),
                inScope(definition).orderByDesc(WorkflowRevision::getRevisionNumber));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(WorkflowRevisionView::of).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDef createDraft(String wfId, String revisionId, String name) {
        WorkflowDef definition = requireWorkflow(wfId, true);
        String draftName = requireText(name, 200, "草稿名称");
        WorkflowRevision revision = revisions.selectOne(inScope(definition)
                .eq(WorkflowRevision::getId, revisionId).select(WorkflowRevision::getGraphJson));
        if (revision == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流版本不存在");
        }
        GraphSnapshot snapshot;
        try {
            snapshot = json.readValue(revision.getGraphJson(), GraphSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取工作流版本", e);
        }

        WorkflowDef draft = new WorkflowDef();
        draft.setName(draftName);
        draft.setCode("wf_" + UUID.randomUUID());
        draft.setProjectId(definition.getProjectId());
        draft.setStatus(0);
        draft.setParams(snapshot.params());
        WorkflowGraphRequest request = new WorkflowGraphRequest();
        request.setDef(draft);
        request.setNodes(snapshot.nodes());
        request.setEdges(snapshot.edges());
        return workflows.saveGraph(request);
    }

    private WorkflowDef requireWorkflow(String wfId, boolean lock) {
        if (StrUtil.isBlank(MetadataHolder.getUserId())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        var query = Wrappers.<WorkflowDef>lambdaQuery().eq(WorkflowDef::getId, wfId);
        if (lock) {
            query.last("FOR UPDATE");
        }
        WorkflowDef definition = definitions.selectOne(query);
        if (definition == null || (StrUtil.isBlank(definition.getProjectId()) && !MetadataHolder.isSuperAdmin())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在或不可访问");
        }
        access.requireAction(definition.getProjectId(), "task:develop");
        return definition;
    }

    private LambdaQueryWrapper<WorkflowRevision> inScope(WorkflowDef definition) {
        String projectId = StrUtil.trimToNull(definition.getProjectId());
        var query = Wrappers.<WorkflowRevision>lambdaQuery().eq(WorkflowRevision::getWfId, definition.getId());
        return projectId == null ? query.isNull(WorkflowRevision::getProjectId)
                : query.eq(WorkflowRevision::getProjectId, projectId);
    }

    private String requireText(String text, int maxLength, String field) {
        String normalized = StrUtil.trimToNull(text);
        if (normalized == null || normalized.length() > maxLength) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, field + "必填且最多 " + maxLength + " 字");
        }
        return normalized;
    }

    private record GraphSnapshot(String params, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {}
}
