package com.datakhaos.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.entity.FlinkAsset;
import com.datakhaos.workflow.entity.FlinkAsset.Kind;
import com.datakhaos.workflow.entity.FlinkAssetVersion;
import com.datakhaos.workflow.flink.FlinkDefinition;
import com.datakhaos.workflow.flink.FlinkDefinition.FunctionRef;
import com.datakhaos.workflow.flink.FlinkDefinition.JarJob;
import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import com.datakhaos.workflow.flink.FlinkVersionView;
import com.datakhaos.workflow.flink.FlinkVersionView.Revision;
import com.datakhaos.workflow.mapper.FlinkAssetMapper;
import com.datakhaos.workflow.mapper.FlinkAssetVersionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FlinkStudioService {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern JVM_CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Set<String> JAR_SCHEMES = Set.of("file", "hdfs", "s3", "s3a", "oss");
    private static final int MAX_SQL_LENGTH = 128 * 1024;

    private final FlinkAssetMapper assets;
    private final FlinkAssetVersionMapper versions;
    private final WorkflowProjectAccess access;
    private final ObjectMapper json;

    public PageResult<FlinkAsset> page(String projectId, Kind kind, String keyword, long current, long size) {
        authorize(projectId);
        validatePage(current, size);
        if (kind == null || (keyword != null && keyword.length() > 200)) {
            throw invalid("资产类型或关键字无效");
        }
        var page = assets.selectPage(new Page<>(current, size), Wrappers.<FlinkAsset>lambdaQuery()
                .eq(FlinkAsset::getProjectId, projectId).eq(FlinkAsset::getKind, kind)
                .and(keyword != null && !keyword.isBlank(), query -> query
                        .like(FlinkAsset::getName, keyword).or().like(FlinkAsset::getCode, keyword))
                .orderByDesc(FlinkAsset::getUpdateTime, FlinkAsset::getId));
        if (kind == Kind.FUNCTION && !page.getRecords().isEmpty()) {
            var rows = page.getRecords();
            var definitions = versions.selectList(Wrappers.<FlinkAssetVersion>lambdaQuery()
                    .select(FlinkAssetVersion::getAssetId, FlinkAssetVersion::getVersionNumber,
                            FlinkAssetVersion::getDefinitionJson)
                    .and(query -> rows.forEach(row -> query.or(pair -> pair
                            .eq(FlinkAssetVersion::getAssetId, row.getId())
                            .eq(FlinkAssetVersion::getVersionNumber, row.getCurrentVersion())))));
            var byAsset = definitions.stream().collect(Collectors.toMap(
                    FlinkAssetVersion::getAssetId, this::readDefinition));
            rows.forEach(row -> {
                var definition = byAsset.get(row.getId());
                if (definition != null) row.setFunctionType(definition.functionType());
            });
        }
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public FlinkVersionView create(String projectId, Kind kind, String code, String name, String note, FlinkDefinition input) {
        authorize(projectId);
        if (kind == null || code == null || !IDENTIFIER.matcher(code).matches()) {
            throw invalid("编码须使用小写字母、数字或下划线，并以小写字母开头");
        }
        String title = required(name, 200, "名称");
        String comment = required(note, 500, "版本说明");
        FlinkDefinition definition = validate(projectId, kind, input);

        var asset = new FlinkAsset();
        asset.setProjectId(projectId);
        asset.setKind(kind);
        asset.setCode(code);
        asset.setName(title);
        asset.setCurrentVersion(1);
        asset.setCurrentMode(definition.mode());
        asset.setCreateTime(LocalDateTime.now());
        asset.setUpdateTime(asset.getCreateTime());
        try {
            assets.insert(asset);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "同项目已有同类型的资产编码");
        }
        return insertVersion(asset, comment, definition, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public FlinkVersionView save(String projectId, String id, Integer expectedVersion,
                                 String name, String note, FlinkDefinition input) {
        authorize(projectId);
        FlinkAsset asset = asset(projectId, id, true);
        checkCurrent(asset, expectedVersion);
        String title = required(name, 200, "名称");
        String comment = required(note, 500, "版本说明");
        FlinkDefinition definition = validate(projectId, asset.getKind(), input);
        return append(asset, title, comment, definition, null);
    }

    public PageResult<Revision> history(String projectId, String id, long current, long size) {
        authorize(projectId);
        validatePage(current, size);
        FlinkAsset asset = asset(projectId, id, false);
        var page = versions.selectPage(new Page<>(current, size), Wrappers.<FlinkAssetVersion>lambdaQuery()
                .eq(FlinkAssetVersion::getAssetId, asset.getId())
                .orderByDesc(FlinkAssetVersion::getVersionNumber));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(Revision::of).toList());
    }

    public FlinkVersionView detail(String projectId, String id, int number) {
        authorize(projectId);
        FlinkAsset asset = asset(projectId, id, false);
        FlinkAssetVersion row = version(asset.getId(), number);
        return view(asset, row, readDefinition(row));
    }

    @Transactional(rollbackFor = Exception.class)
    public FlinkVersionView restore(String projectId, String id, int number, Integer expectedVersion, String note) {
        authorize(projectId);
        FlinkAsset asset = asset(projectId, id, true);
        checkCurrent(asset, expectedVersion);
        String comment = required(note, 500, "恢复说明");
        FlinkAssetVersion source = version(asset.getId(), number);
        return append(asset, source.getName(), comment, readDefinition(source), number);
    }

    public String exportSql(String projectId, String id, int number) {
        return exportSql(projectId, detail(projectId, id, number));
    }

    String exportSql(String projectId, FlinkVersionView job) {
        if (job.kind() != Kind.JOB) {
            throw invalid("仅 SQL 作业版本可以导出 SQL");
        }
        FlinkDefinition definition = job.definition();
        var output = new StringBuilder()
                .append("-- Flink job ").append(job.code()).append(" v").append(job.revision().version()).append('\n')
                .append("SET 'execution.runtime-mode' = '")
                .append(definition.mode().name().toLowerCase(Locale.ROOT)).append("';\n")
                .append("SET 'parallelism.default' = '").append(definition.parallelism()).append("';\n");
        for (FunctionRef ref : definition.functions()) {
            FlinkAsset function = requireFunction(projectId, ref.assetId());
            FlinkDefinition udf = readDefinition(version(function.getId(), ref.version()));
            output.append("-- Function ").append(function.getCode()).append(" v").append(ref.version()).append('\n')
                    .append("CREATE TEMPORARY FUNCTION `").append(function.getCode())
                    .append("` AS ").append(literal(udf.className()))
                    .append(" LANGUAGE ").append(udf.language());
            if (udf.jarUri() != null) {
                output.append(" USING JAR ").append(literal(udf.jarUri()));
            }
            output.append(";\n");
        }
        return output.append('\n').append(definition.sql()).append('\n').toString();
    }

    private FlinkVersionView append(FlinkAsset asset, String name, String note, FlinkDefinition definition, Integer restoredFrom) {
        int current = asset.getCurrentVersion();
        int next = current + 1;
        LocalDateTime now = LocalDateTime.now();
        int updated = assets.update(null, Wrappers.<FlinkAsset>lambdaUpdate()
                .eq(FlinkAsset::getId, asset.getId()).eq(FlinkAsset::getCurrentVersion, current)
                .set(FlinkAsset::getCurrentVersion, next).set(FlinkAsset::getCurrentMode, definition.mode())
                .set(FlinkAsset::getName, name).set(FlinkAsset::getUpdateTime, now));
        if (updated != 1) {
            throw conflict();
        }
        asset.setCurrentVersion(next);
        asset.setCurrentMode(definition.mode());
        asset.setName(name);
        asset.setUpdateTime(now);
        return insertVersion(asset, note, definition, restoredFrom);
    }

    private FlinkVersionView insertVersion(FlinkAsset asset, String note, FlinkDefinition definition, Integer restoredFrom) {
        var row = new FlinkAssetVersion();
        row.setAssetId(asset.getId());
        row.setVersionNumber(asset.getCurrentVersion());
        row.setName(asset.getName());
        row.setNote(note);
        row.setCreatedBy(MetadataHolder.getUserId());
        row.setRestoredFrom(restoredFrom);
        row.setCreateTime(asset.getUpdateTime());
        try {
            row.setDefinitionJson(json.writeValueAsString(definition));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存 Flink 定义", e);
        }
        versions.insert(row);
        return view(asset, row, definition);
    }

    private FlinkVersionView view(FlinkAsset asset, FlinkAssetVersion row, FlinkDefinition definition) {
        return new FlinkVersionView(asset.getId(), asset.getCode(), asset.getKind(), asset.getCurrentVersion(),
                Revision.of(row), definition);
    }

    private FlinkDefinition readDefinition(FlinkAssetVersion row) {
        try {
            return json.readValue(row.getDefinitionJson(), FlinkDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取 Flink 历史定义", e);
        }
    }

    private FlinkAsset asset(String projectId, String id, boolean lock) {
        var query = Wrappers.<FlinkAsset>lambdaQuery()
                .eq(FlinkAsset::getId, id).eq(FlinkAsset::getProjectId, projectId);
        if (lock) {
            query.last("FOR UPDATE");
        }
        FlinkAsset asset = assets.selectOne(query);
        if (asset == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Flink 资产不存在或不可见");
        }
        return asset;
    }

    private FlinkAsset requireFunction(String projectId, String id) {
        FlinkAsset function = asset(projectId, id, false);
        if (function.getKind() != Kind.FUNCTION) {
            throw invalid("作业依赖必须是函数版本");
        }
        return function;
    }

    private FlinkAssetVersion version(String assetId, int number) {
        if (number < 1) {
            throw invalid("版本号须为正整数");
        }
        FlinkAssetVersion row = versions.selectOne(Wrappers.<FlinkAssetVersion>lambdaQuery()
                .select(FlinkAssetVersion::getVersionNumber, FlinkAssetVersion::getName, FlinkAssetVersion::getNote,
                        FlinkAssetVersion::getCreatedBy, FlinkAssetVersion::getRestoredFrom,
                        FlinkAssetVersion::getCreateTime, FlinkAssetVersion::getDefinitionJson)
                .eq(FlinkAssetVersion::getAssetId, assetId).eq(FlinkAssetVersion::getVersionNumber, number));
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "版本不存在");
        }
        return row;
    }

    private FlinkDefinition validate(String projectId, Kind kind, FlinkDefinition input) {
        if (input == null) {
            throw invalid("定义必填");
        }
        if (kind == Kind.FUNCTION) {
            if (input.jarJob() != null || input.sql() != null || input.mode() != null || input.parallelism() != null
                    || (input.functions() != null && !input.functions().isEmpty())) {
                throw invalid("函数定义只能包含注册参数");
            }
            String className = required(input.className(), 255, "函数类名");
            if (input.language() == null || !JVM_CLASS.matcher(className).matches()) {
                throw invalid("请选择函数语言并填写 JVM 全限定类名");
            }
            return new FlinkDefinition(null, null, null, null, input.language(), className, jarUri(input.jarUri()),
                    input.functionType(), null);
        }
        if (input.functionType() != null || input.language() != null || input.className() != null || input.jarUri() != null) {
            throw invalid("作业通过函数版本引用登记 UDF");
        }
        if (input.mode() == null || input.parallelism() == null || input.parallelism() < 1 || input.parallelism() > 1024) {
            throw invalid("请选择流或批模式，并填写 1 至 1024 的并行度");
        }
        if (kind == Kind.JAR_JOB) {
            if (input.sql() != null || (input.functions() != null && !input.functions().isEmpty()) || input.jarJob() == null) {
                throw invalid("JAR 作业须配置制品，且不能包含 SQL 或函数引用");
            }
            var jar = input.jarJob();
            String jarId = required(jar.jarId(), 255, "Flink JAR ID");
            if (!FlinkRuntimeClient.validJarId(jarId)) throw invalid("Flink JAR ID 格式无效");
            String entry = required(jar.entryClass(), 255, "入口类");
            if (!JVM_CLASS.matcher(entry).matches()) throw invalid("请填写 JVM 全限定入口类名");
            var arguments = jar.arguments() == null ? List.<String>of() : jar.arguments();
            if (arguments.size() > 64 || arguments.stream().anyMatch(arg -> arg == null
                    || arg.length() > 2048 || arg.indexOf(0) >= 0)) {
                throw invalid("最多 64 个启动参数，每项不超过 2048 字符且不能含 NUL");
            }
            return new FlinkDefinition(input.mode(), input.parallelism(), null, null, null, null, null,
                    null, new JarJob(jarId, entry, List.copyOf(arguments)));
        }
        if (input.jarJob() != null) throw invalid("SQL 作业不接受 JAR 作业配置");
        String sql = required(input.sql(), MAX_SQL_LENGTH, "SQL");
        List<FunctionRef> refs = new ArrayList<>();
        if (input.functions() != null) {
            if (input.functions().size() > 20) {
                throw invalid("最多引用 20 个函数");
            }
            Set<String> seen = new HashSet<>();
            for (FunctionRef ref : input.functions()) {
                if (ref == null || ref.assetId() == null || ref.assetId().isBlank() || ref.version() == null) {
                    throw invalid("函数引用须包含资产 ID 和版本号");
                }
                FlinkAsset function = requireFunction(projectId, ref.assetId());
                if (!seen.add(function.getId())) {
                    throw invalid("同一个函数只能引用一个版本");
                }
                version(function.getId(), ref.version());
                refs.add(new FunctionRef(function.getId(), ref.version()));
            }
        }
        return new FlinkDefinition(input.mode(), input.parallelism(), sql, List.copyOf(refs), null, null, null, null, null);
    }

    private String jarUri(String value) {
        if (value == null || value.isBlank()) return null;
        String text = required(value, 1024, "JAR 地址");
        try {
            URI uri = URI.create(text);
            if (uri.getScheme() != null && JAR_SCHEMES.contains(uri.getScheme()) && uri.getPath() != null
                    && uri.getPath().startsWith("/") && uri.getPath().endsWith(".jar")
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return text;
            }
        } catch (IllegalArgumentException ignored) {
            // Return the same validation result for all unsupported URI forms.
        }
        throw invalid("JAR 地址须为不带认证信息的 file、hdfs、s3、s3a 或 oss 绝对地址");
    }

    private void authorize(String projectId) {
        if (MetadataHolder.getUserId() == null || MetadataHolder.getUserId().isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw invalid("项目 ID 无效");
        }
        access.requireAction(projectId, "task:develop");
    }

    private void validatePage(long current, long size) {
        if (current < 1 || size < 1 || size > 100) {
            throw invalid("页码须为正整数，每页限制 1 至 100 条");
        }
    }

    private void checkCurrent(FlinkAsset asset, Integer expected) {
        if (expected == null || expected < 1) throw invalid("请提供当前版本号");
        if (!expected.equals(asset.getCurrentVersion())) throw conflict();
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw invalid(label + "必填且长度不得超过 " + max);
        }
        return value.strip();
    }

    private String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.VALIDATE_FAILED, message);
    }

    private BusinessException conflict() {
        return new BusinessException(409, "资产已被修改，请重新加载最新版本");
    }
}
