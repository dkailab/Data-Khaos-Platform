package com.datakhaos.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.entity.FlinkAsset;
import com.datakhaos.workflow.entity.FlinkDeployment;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.flink.FlinkDefinition.Mode;
import com.datakhaos.workflow.flink.FlinkRunSql;
import com.datakhaos.workflow.flink.FlinkCheckpointView;
import com.datakhaos.workflow.flink.FlinkMetricsView;
import com.datakhaos.workflow.flink.FlinkRunView;
import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import com.datakhaos.workflow.flink.FlinkRuntimeClient.EngineJob;
import com.datakhaos.workflow.flink.FlinkRuntimeClient.RuntimeFailure;
import com.datakhaos.workflow.mapper.FlinkAssetMapper;
import com.datakhaos.workflow.mapper.FlinkDeploymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FlinkDeploymentService {
    private static final String DEVELOP = "task:develop";
    private static final String SCHEDULE = "task:schedule";
    private static final Duration SUBMIT_BUDGET = Duration.ofSeconds(22);

    private final FlinkDeploymentMapper runs;
    private final FlinkAssetMapper assets;
    private final FlinkStudioService studio;
    private final WorkflowProjectAccess access;
    private final FlinkRuntimeClient runtime;

    public RunPage page(String project, String assetId, long current, long size) {
        authorize(project, DEVELOP);
        if (current < 1 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数无效");
        }
        if (!assets.exists(Wrappers.<FlinkAsset>lambdaQuery().eq(FlinkAsset::getId, assetId)
                .eq(FlinkAsset::getProjectId, project).eq(FlinkAsset::getKind, FlinkAsset.Kind.JOB))) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Flink 作业不存在或不可见");
        }
        var page = runs.selectPage(new Page<>(current, size), Wrappers.<FlinkDeployment>lambdaQuery()
                .eq(FlinkDeployment::getProjectId, project).eq(FlinkDeployment::getAssetId, assetId)
                .orderByDesc(FlinkDeployment::getCreateTime, FlinkDeployment::getId));
        return new RunPage(runtime.enabled(project), PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(FlinkRunView::of).toList()));
    }

    public FlinkRunView submit(String project, String assetId, int version, String inputKey) {
        return submit(project, assetId, version, inputKey, null);
    }

    public FlinkRunView resume(String project, String assetId, String sourceId, String inputKey) {
        authorize(project, SCHEDULE);
        authorize(project, DEVELOP);
        FlinkDeployment source = find(project, assetId, sourceId);
        requireBinding(source);
        if (source.getState() != State.STOPPED || source.getSavepointPath() == null) {
            throw new BusinessException(409, "运行尚未取得可恢复的保存点");
        }
        return submit(project, assetId, source.getVersionNumber(), inputKey, source);
    }

    private FlinkRunView submit(String project, String assetId, int version, String inputKey, FlinkDeployment source) {
        authorize(project, SCHEDULE);
        authorize(project, DEVELOP);
        String requestId = requestId(inputKey);
        String sourceId = source == null ? null : source.getId();
        FlinkDeployment prior = byRequest(project, requestId);
        if (prior != null) return sameRequest(prior, assetId, version, sourceId);
        if (!runtime.enabled(project)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前项目未绑定 Flink 运行集群");
        }
        FlinkRunSql script = FlinkRunSql.parse(studio.exportSql(project, assetId, version));
        FlinkDeployment run = register(project, assetId, version, requestId, sourceId);
        if (run == null) {
            prior = byRequest(project, requestId);
            if (prior != null) return sameRequest(prior, assetId, version, sourceId);
            throw new BusinessException(409, "此作业已有活动运行，请先查看或刷新运行记录");
        }
        return execute(run, script, source == null ? null : source.getSavepointPath());
    }

    private FlinkRunView execute(FlinkDeployment run, FlinkRunSql script, String recoveryPath) {
        String session = null;
        boolean close = true;
        long deadline = System.nanoTime() + SUBMIT_BUDGET.toNanos();
        try {
            session = runtime.open(engineName(run), recoveryPath);
            for (String statement : script.setup()) runtime.awaitSetup(session, statement, deadline);
            runtime.awaitSetup(session, "SET 'pipeline.name' = '" + engineName(run) + "'", deadline);
            if (!transition(run, State.SUBMITTING, null, null, null)) return reload(run);

            // Only this request passed PREPARING → SUBMITTING. Any ambiguity after this point preserves the run lock.
            run.setState(State.SUBMITTING);
            run.setRowVersion(run.getRowVersion() + 1);
            close = false;
            Optional<String> jobId = runtime.insert(session, script.insert(), deadline);
            if (jobId.isPresent()) {
                close = true;
                transition(run, State.SUBMITTED, jobId.get(), null, null);
            } else {
                transition(run, State.RECONCILING, null, null, "Flink 提交结果待确认，请刷新运行");
            }
        } catch (RuntimeFailure e) {
            if (run.getState() == State.PREPARING) {
                transition(run, State.FAILED, null, null, e.getMessage());
            } else {
                transition(run, State.RECONCILING, null, null, "Flink 提交结果待确认，请刷新运行");
            }
        } finally {
            if (session != null && close) {
                try {
                    runtime.close(session);
                } catch (RuntimeFailure ignored) {
                    // The gateway expires idle sessions. The persisted run state remains authoritative.
                }
            }
        }
        return reload(run);
    }

    public FlinkRunView refresh(String project, String assetId, String id) {
        authorize(project, DEVELOP);
        FlinkDeployment run = find(project, assetId, id);
        if (run.getState().terminal()) return FlinkRunView.of(run);
        requireBinding(run);
        if (run.getState() == State.PREPARING) {
            if (run.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(2))) {
                transition(run, State.FAILED, null, null, "提交前准备中断，未发送作业写入语句");
            }
            return reload(run);
        }
        try {
            if (run.getState() == State.STOP_REQUESTED) return refreshStop(run);
            Optional<EngineJob> observed = run.getEngineJobId() == null
                    ? runtime.findByName(engineName(run)) : Optional.of(runtime.job(run.getEngineJobId()));
            if (observed.isPresent()) {
                EngineJob job = observed.get();
                if (run.getEngineJobId() != null && !run.getEngineJobId().equals(job.id())) {
                    throw new RuntimeFailure("Flink 返回的运行 ID 不匹配");
                }
                State state = switch (job.status()) {
                    case "FINISHED" -> State.FINISHED;
                    case "FAILED" -> State.FAILED;
                    case "CANCELED" -> State.CANCELED;
                    default -> run.getState() == State.CANCEL_REQUESTED ? State.CANCEL_REQUESTED : State.SUBMITTED;
                };
                transition(run, state, job.id(), job.status(), null);
            } else if (run.getState() == State.SUBMITTING
                    && run.getUpdateTime().isBefore(LocalDateTime.now().minusMinutes(2))) {
                transition(run, State.RECONCILING, null, null, "Flink 提交结果待确认，请刷新运行");
            }
            return reload(run);
        } catch (RuntimeFailure e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, e.getMessage());
        }
    }

    public FlinkCheckpointView checkpoints(String project, String assetId, String id) {
        String jobId = observedJob(project, assetId, id);
        try {
            return runtime.checkpoints(jobId);
        } catch (RuntimeFailure e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "暂时无法读取 Flink 检查点统计");
        }
    }

    public FlinkMetricsView metrics(String project, String assetId, String id, String vertexId) {
        String jobId = observedJob(project, assetId, id);
        FlinkMetricsView view;
        try {
            view = runtime.metrics(jobId, vertexId);
        } catch (RuntimeFailure e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "暂时无法读取 Flink 运行指标");
        }
        if (vertexId != null && view.selectedVertexId() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Flink 执行节点不存在或不可见");
        }
        return view;
    }

    private String observedJob(String project, String assetId, String id) {
        authorize(project, DEVELOP);
        FlinkDeployment run = find(project, assetId, id);
        requireBinding(run);
        if (run.getEngineJobId() == null) {
            throw new BusinessException(409, "引擎作业 ID 尚未确认");
        }
        return run.getEngineJobId();
    }

    public FlinkRunView cancel(String project, String assetId, String id) {
        authorize(project, SCHEDULE);
        authorize(project, DEVELOP);
        FlinkDeployment run = find(project, assetId, id);
        if (run.getState().terminal()) return FlinkRunView.of(run);
        requireBinding(run);
        if ((run.getState() != State.SUBMITTED && run.getState() != State.CANCEL_REQUESTED)
                || run.getEngineJobId() == null) {
            throw new BusinessException(409, "运行尚未确认，或已有停机操作");
        }
        if (run.getState() == State.SUBMITTED
                && !transition(run, State.CANCEL_REQUESTED, run.getEngineJobId(), run.getEngineState(), null)) {
            return reload(run);
        }
        try {
            runtime.cancel(run.getEngineJobId());
        } catch (RuntimeFailure e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "Flink 未确认取消请求，请刷新或重试");
        }
        return reload(run);
    }

    public FlinkRunView stopWithSavepoint(String project, String assetId, String id) {
        authorize(project, SCHEDULE);
        authorize(project, DEVELOP);
        FlinkDeployment run = find(project, assetId, id);
        if (run.getState() == State.STOPPED) return FlinkRunView.of(run);
        requireBinding(run);
        if ((run.getState() != State.SUBMITTED && run.getState() != State.STOP_REQUESTED)
                || run.getEngineJobId() == null
                || studio.detail(project, assetId, run.getVersionNumber()).definition().mode() != Mode.STREAMING) {
            throw new BusinessException(409, "仅已提交的流式作业支持停机保存");
        }
        String trigger = run.getSavepointTriggerId();
        if (run.getState() == State.SUBMITTED) {
            trigger = UUID.randomUUID().toString().replace("-", "");
            if (!update(run, State.STOP_REQUESTED, run.getEngineJobId(), run.getEngineState(), null, trigger, null)) {
                return reload(run);
            }
        }
        try {
            runtime.stopWithSavepoint(run.getEngineJobId(), trigger);
        } catch (RuntimeFailure e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存点请求待核查，可刷新或重试同一操作");
        }
        return reload(run);
    }

    private FlinkRunView refreshStop(FlinkDeployment run) {
        var savepoint = runtime.savepoint(run.getEngineJobId(), run.getSavepointTriggerId());
        if (!savepoint.completed()) return FlinkRunView.of(run);
        EngineJob job = runtime.job(run.getEngineJobId());
        if (!run.getEngineJobId().equals(job.id())) throw new RuntimeFailure("Flink 返回的运行 ID 不匹配");
        State terminal = switch (job.status()) {
            case "FINISHED" -> State.FINISHED;
            case "CANCELED" -> State.CANCELED;
            case "FAILED" -> State.FAILED;
            default -> null;
        };
        if (savepoint.location() == null) {
            update(run, terminal == null ? State.SUBMITTED : terminal, job.id(), job.status(),
                    "Flink 未生成可恢复保存点", null, null);
        } else if (terminal == State.FINISHED || terminal == State.CANCELED) {
            update(run, State.STOPPED, job.id(), job.status(), null, run.getSavepointTriggerId(), savepoint.location());
        } else if (terminal == State.FAILED) {
            update(run, State.FAILED, job.id(), job.status(), "保存停机过程中作业失败", null, null);
        }
        return reload(run);
    }

    private FlinkDeployment register(String project, String assetId, int version, String requestId, String sourceId) {
        FlinkDeployment run = new FlinkDeployment();
        run.setProjectId(project);
        run.setAssetId(assetId);
        run.setVersionNumber(version);
        run.setRequestId(requestId);
        run.setRestoredFromRunId(sourceId);
        run.setOperatorId(MetadataHolder.getUserId());
        run.setClusterKey(runtime.clusterKey());
        run.setState(State.PREPARING);
        run.setRowVersion(0);
        run.setActiveAssetId(assetId);
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(run.getCreateTime());
        try {
            runs.insert(run);
            return run;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    private boolean transition(FlinkDeployment run, State state, String jobId, String engineState, String message) {
        return update(run, state, jobId, engineState, message, run.getSavepointTriggerId(), run.getSavepointPath());
    }

    private boolean update(FlinkDeployment run, State state, String jobId, String engineState, String message,
                           String triggerId, String path) {
        return runs.update(null, Wrappers.<FlinkDeployment>lambdaUpdate()
                .eq(FlinkDeployment::getId, run.getId()).eq(FlinkDeployment::getRowVersion, run.getRowVersion())
                .set(FlinkDeployment::getRowVersion, run.getRowVersion() + 1)
                .set(FlinkDeployment::getState, state).set(FlinkDeployment::getEngineJobId, jobId)
                .set(FlinkDeployment::getEngineState, engineState).set(FlinkDeployment::getMessage, message)
                .set(FlinkDeployment::getSavepointTriggerId, triggerId).set(FlinkDeployment::getSavepointPath, path)
                .set(FlinkDeployment::getActiveAssetId, state.terminal() ? null : run.getAssetId())
                .set(FlinkDeployment::getUpdateTime, LocalDateTime.now())) == 1;
    }

    private FlinkDeployment byRequest(String project, String requestId) {
        return runs.selectOne(Wrappers.<FlinkDeployment>lambdaQuery()
                .eq(FlinkDeployment::getProjectId, project).eq(FlinkDeployment::getRequestId, requestId));
    }

    private FlinkDeployment find(String project, String assetId, String id) {
        FlinkDeployment run = runs.selectOne(Wrappers.<FlinkDeployment>lambdaQuery().eq(FlinkDeployment::getId, id)
                .eq(FlinkDeployment::getProjectId, project).eq(FlinkDeployment::getAssetId, assetId));
        if (run == null) throw new BusinessException(ResultCode.NOT_FOUND, "Flink 运行不存在或不可见");
        return run;
    }

    private FlinkRunView reload(FlinkDeployment run) {
        return FlinkRunView.of(find(run.getProjectId(), run.getAssetId(), run.getId()));
    }

    private FlinkRunView sameRequest(FlinkDeployment run, String asset, int version, String sourceId) {
        if (!run.getAssetId().equals(asset) || run.getVersionNumber() != version
                || !Objects.equals(run.getRestoredFromRunId(), sourceId)) {
            throw new BusinessException(409, "请求标识已用于其他作业版本");
        }
        return FlinkRunView.of(run);
    }

    private String requestId(String input) {
        if (input != null && input.length() == 36) {
            try {
                return UUID.fromString(input).toString();
            } catch (IllegalArgumentException ignored) {
                // Return a validation response for invalid identifiers.
            }
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "请求标识须为 UUID");
    }

    private void authorize(String project, String capability) {
        if (MetadataHolder.getUserId() == null || MetadataHolder.getUserId().isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (project == null || !project.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "项目 ID 无效");
        }
        access.requireAction(project, capability);
    }

    private void requireBinding(FlinkDeployment run) {
        if (!runtime.enabled(run.getProjectId()) || !runtime.clusterKey().equals(run.getClusterKey())) {
            throw new BusinessException(409, "运行所属集群不可用，请核对绑定配置");
        }
    }

    private String engineName(FlinkDeployment run) {
        return "dk-flink-" + run.getId();
    }

    public record RunPage(boolean enabled, PageResult<FlinkRunView> page) {}
}
