package com.datakhaos.metadata.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.dquality.api.model.DqSnapshotDto;
import com.datakhaos.dquality.api.service.QualityApiClient;
import com.datakhaos.metadata.dto.AssetEvidenceDto;
import com.datakhaos.metadata.dto.AssetTable;
import com.datakhaos.metadata.dto.RunEvidenceDto;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.pipeline.api.model.PipelineTaskDto;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import com.datakhaos.permission.api.model.ProjectGroupDto;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.workflow.api.model.WorkflowNodeRunDto;
import com.datakhaos.workflow.api.model.WorkflowRunDto;
import com.datakhaos.workflow.api.service.WorkflowApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PR3 运行证据聚合：把工作流运行、管道实例与质量快照串成可追溯证据。
 *
 * <p>只读，不新增表；跨服务调用沿用系统身份（各客户端内部透传 SUPER_ADMIN）。
 * 结构血缘仍以 {@code meta_table_lineage} 为准，这里只回答
 * 「哪个运行实际生产了这个分区、质量结论是什么」。</p>
 */
@Service
@RequiredArgsConstructor
public class RunEvidenceService {

    private final WorkflowApiClient workflowApi;
    private final PipelineApiClient pipelineApi;
    private final QualityApiClient qualityApi;
    private final AssetCatalogService assetCatalog;
    private final PermissionApiClient permission;

    /** 从 FROM / JOIN 后面提取表名（含库限定），用于没有显式 sourceTable 声明的任务 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+`?([a-zA-Z0-9_]+)`?(?:\\s*\\.\\s*`?([a-zA-Z0-9_]+)`?)?");

    /** 运行证据：runId -> 节点 -> 管道实例/质量快照 -> 输入输出资产 */
    public RunEvidenceDto runEvidence(String runId) {
        if (StrUtil.isBlank(runId)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED);
        }
        WorkflowRunDto run = workflowApi.getRun(runId);
        if (run == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "运行不存在或不可查询: " + runId);
        }
        assertAccessible(projects(run.getProjectGroupId()));
        List<WorkflowNodeRunDto> nodeRuns = workflowApi.nodeRuns(runId);
        List<RunEvidenceDto.NodeEvidenceDto> nodes = new ArrayList<>();
        Set<String> declaredGroups = projects(run.getProjectGroupId());

        for (WorkflowNodeRunDto node : nodeRuns) {
            RunEvidenceDto.NodeEvidenceDto item = new RunEvidenceDto.NodeEvidenceDto();
            item.setNodeRunId(node.getId());
            item.setNodeCode(node.getNodeCode());
            item.setNodeName(node.getNodeName());
            item.setNodeType(node.getNodeType());
            item.setStatus(node.getStatus());
            item.setStartTime(node.getStartTime());
            item.setEndTime(node.getEndTime());
            item.setDurationMs(node.getDurationMs());
            item.setErrorMessage(node.getErrorMessage());
            item.setRefType(node.getRefType());
            item.setRefId(node.getRefId());

            PipelineTaskDto task = null;
            if ("PIPELINE".equals(node.getRefType()) && StrUtil.isNotBlank(node.getRefId())) {
                PipelineInstanceDto instance = pipelineApi.getInstance(node.getRefId());
                item.setPipeline(instance);
                if (instance != null) {
                    task = pipelineApi.getTask(instance.getTaskId());
                    declaredGroups.addAll(projects(instance.getProjectGroupId(),
                            task == null ? null : task.getProjectGroupId()));
                }
            } else if ("QUALITY".equals(node.getRefType()) && StrUtil.isNotBlank(node.getRefId())) {
                DqSnapshotDto snapshot = qualityApi.getSnapshot(node.getRefId());
                item.setQuality(snapshot);
                if (snapshot != null && StrUtil.isNotBlank(snapshot.getProjectGroupId())) {
                    declaredGroups.add(snapshot.getProjectGroupId());
                }
            }

            item.setInputAssets(inputAssets(task, item.getPipeline()));
            item.setOutputAssets(outputAssets(task, item.getPipeline()));
            nodes.add(item);
        }

        assertAccessible(declaredGroups);
        RunEvidenceDto result = new RunEvidenceDto();
        result.setRun(run);
        result.setNodes(nodes);
        result.setBizDate(run.getBizDate());
        return result;
    }

    /** 资产证据：从数据地图的表回查某业务日分区的发布与质量证据 */
    public AssetEvidenceDto assetEvidence(String assetId, String bizDate) {
        if (StrUtil.isBlank(assetId)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED);
        }
        // 复用数据地图的可见性校验：跨项目/无权限直接 NOT_FOUND
        AssetCatalogService.Detail detail = assetCatalog.detail(assetId);
        AssetTable asset = detail.table();

        if (StrUtil.isNotBlank(bizDate)) {
            try {
                bizDate = LocalDate.parse(bizDate).toString();
            } catch (DateTimeParseException e) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "业务日期格式应为 YYYY-MM-DD");
            }
        }

        List<PipelineInstanceDto> published = pipelineApi.findByTarget(asset.getDatasourceId(),
                asset.getDatabaseName(), asset.getTableName(), bizDate, true, 1);
        PipelineInstanceDto instance = published.stream().findFirst().orElse(null);
        WorkflowRunDto run = instance == null || StrUtil.isBlank(instance.getWorkflowRunId())
                ? null : workflowApi.getRun(instance.getWorkflowRunId());
        Set<String> visibleProjects = visibleProjects();
        if (instance != null && !canRead(visibleProjects, projects(instance.getProjectGroupId(),
                run == null ? null : run.getProjectGroupId()))) {
            instance = null;
            run = null;
        }
        DqSnapshotDto snapshot = instance == null || run == null ? null
                : publicationSnapshot(instance, detail, visibleProjects);

        AssetEvidenceDto result = new AssetEvidenceDto();
        result.setAsset(asset);
        result.setBizDate(bizDate);
        result.setPublishedInstance(instance);
        result.setPublishedRun(run);
        result.setQualityPass(snapshot);
        result.setQualityLatest(snapshot);
        result.setQualitySourceTable(snapshot == null ? null : snapshot.getTableName());
        result.setRecentInstances(pipelineApi.findByTarget(asset.getDatasourceId(), asset.getDatabaseName(),
                        asset.getTableName(), bizDate, false, 20).stream()
                .filter(item -> canRead(visibleProjects, projects(item.getProjectGroupId())))
                .toList());
        return result;
    }

    private DqSnapshotDto publicationSnapshot(PipelineInstanceDto instance, AssetCatalogService.Detail detail,
                                               Set<String> visibleProjects) {
        String runId = instance.getWorkflowRunId();
        List<WorkflowNodeRunDto> nodes = workflowApi.nodeRuns(runId);
        WorkflowNodeRunDto writer = nodes.stream()
                .filter(node -> Objects.equals(instance.getWorkflowNodeRunId(), node.getId()))
                .findFirst().orElse(null);
        if (writer == null || writer.getStartTime() == null) {
            return null;
        }
        List<AssetTable> inputs = new ArrayList<>();
        inputs.add(detail.table());
        detail.relations().stream().filter(AssetCatalogService.Relation::upstream)
                .map(AssetCatalogService.Relation::table).forEach(inputs::add);
        return nodes.stream()
                .filter(node -> "QUALITY".equals(node.getRefType()) && "SUCCESS".equals(node.getStatus())
                        && StrUtil.isNotBlank(node.getRefId()) && node.getEndTime() != null
                        && !node.getEndTime().isAfter(writer.getStartTime()))
                .sorted(Comparator.comparing(WorkflowNodeRunDto::getEndTime).reversed()
                        .thenComparing(WorkflowNodeRunDto::getId))
                .map(node -> {
                    DqSnapshotDto snapshot = qualityApi.getSnapshot(node.getRefId());
                    return snapshot != null && Objects.equals(node.getId(), snapshot.getWorkflowNodeRunId())
                            ? snapshot : null;
                })
                .filter(snapshot -> snapshot != null && "PASS".equals(snapshot.getConclusion())
                        && runId.equals(snapshot.getWorkflowRunId())
                        && Objects.equals(instance.getBizDate(), snapshot.getBizDate())
                        && canRead(visibleProjects, projects(snapshot.getProjectGroupId()))
                        && inputs.stream().anyMatch(input -> Objects.equals(input.getDatasourceId(), snapshot.getDatasourceId())
                            && Objects.equals(input.getDatabaseName(), snapshot.getDatabaseName())
                            && Objects.equals(input.getTableName(), snapshot.getTableName())))
                .findFirst().orElse(null);
    }

    /** 输入资产：任务声明的源表，缺省时从源 SQL 解析 */
    private List<String> inputAssets(PipelineTaskDto task, PipelineInstanceDto instance) {
        Set<String> assets = new LinkedHashSet<>();
        if (task != null) {
            if (StrUtil.isNotBlank(task.getSourceTable())) {
                assets.add(task.getSourceTable());
            }
            if (StrUtil.isNotBlank(task.getSourceQuery())) {
                assets.addAll(parseTableRefs(task.getSourceQuery()));
            }
        }
        if (assets.isEmpty() && instance != null && StrUtil.isNotBlank(instance.getTargetTable())) {
            // 无声明时至少给出目标表，避免证据空洞
            assets.add(instance.getTargetTable());
        }
        return List.copyOf(assets);
    }

    /** 输出资产：实例实际写入的目标表优先，回退任务声明 */
    private List<String> outputAssets(PipelineTaskDto task, PipelineInstanceDto instance) {
        Set<String> assets = new LinkedHashSet<>();
        if (instance != null && StrUtil.isNotBlank(instance.getTargetTable())) {
            assets.add(instance.getTargetTable());
        } else if (task != null && StrUtil.isNotBlank(task.getTargetTable())) {
            assets.add(task.getTargetTable());
        }
        return List.copyOf(assets);
    }

    /** 从 SQL 中提取 FROM/JOIN 后的表名（库限定则保留 库.表），去掉字面量子查询噪音 */
    private List<String> parseTableRefs(String sql) {
        Matcher matcher = TABLE_REF.matcher(sql);
        Set<String> refs = new LinkedHashSet<>();
        while (matcher.find()) {
            String first = matcher.group(1);
            String second = matcher.group(2);
            if (second != null) {
                refs.add(first + "." + second);
            } else if (!isSqlKeyword(first)) {
                refs.add(first.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(refs);
    }

    private boolean isSqlKeyword(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "select", "dual", "values", "unnest", "generate_series" -> true;
            default -> false;
        };
    }

    private Set<String> projects(String... groups) {
        return Arrays.stream(groups).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> visibleProjects() {
        String userId = MetadataHolder.getUserId();
        if (StrUtil.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (MetadataHolder.isSuperAdmin()) {
            return Set.of();
        }
        UserPermissionDto perm = permission.getUserPermission(userId);
        return perm == null || perm.getProjectGroups() == null ? Set.of()
                : perm.getProjectGroups().stream().map(ProjectGroupDto::getId)
                        .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
    }

    private boolean canRead(Set<String> visibleProjects, Set<String> declaredGroups) {
        return MetadataHolder.isSuperAdmin() || visibleProjects.containsAll(declaredGroups);
    }

    private void assertAccessible(Set<String> declaredGroups) {
        if (StrUtil.isBlank(MetadataHolder.getUserId())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!declaredGroups.isEmpty() && !canRead(visibleProjects(), declaredGroups)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "运行不存在或不可查询");
        }
    }
}
