package com.datakhaos.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.workflow.entity.FlinkAsset;
import com.datakhaos.workflow.entity.FlinkTableLineage;
import com.datakhaos.workflow.flink.FlinkSqlLineage;
import com.datakhaos.workflow.mapper.FlinkTableLineageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 实时血缘：解析 Flink SQL 的表级上下游，并在能对上元数据资产时回写表血缘。
 *
 * <p>解析结果按「作业 + 版本」保存，版本不可变所以可以整体替换；
 * 只有两端都解析到资产时才写 {@code meta_table_lineage}，避免把 Kafka topic 当成表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlinkLineageService {

    private static final Pattern ASSET_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> TOPIC_CONNECTORS = Set.of("kafka", "upsert-kafka", "pulsar");

    private final FlinkStudioService studio;
    private final FlinkTableLineageMapper lineage;
    private final MetadataLineageClient metadata;

    public record View(String assetId, int version, String sink, List<Node> nodes, List<Edge> edges) {
    }

    public record Node(String name, String assetId, String connector, String physicalName, String role) {
    }

    public record Edge(String source, String target) {
    }

    /** 查询血缘；首次查询时自动解析并落库，便于页面直接打开。 */
    @Transactional(rollbackFor = Exception.class)
    public View view(String projectId, String assetId, int version) {
        List<FlinkTableLineage> rows = rows(assetId, version);
        if (rows.isEmpty()) {
            rows = store(projectId, assetId, version, null);
            mirror(rows);
        }
        return toView(assetId, version, rows);
    }

    /** 显式刷新：重新解析当前版本的 SQL，并回写元数据血缘。 */
    @Transactional(rollbackFor = Exception.class)
    public View refresh(String projectId, String assetId, int version) {
        List<FlinkTableLineage> rows = store(projectId, assetId, version, null);
        mirror(rows);
        return toView(assetId, version, rows);
    }

    /** 作业提交时记录一次真实运行对应的血缘；失败不影响提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void record(String projectId, String assetId, int version, String runId) {
        try {
            List<FlinkTableLineage> rows = store(projectId, assetId, version, runId);
            mirror(rows);
        } catch (Exception e) {
            log.warn("实时血缘解析失败 asset={} version={}: {}", assetId, version, e.getMessage());
        }
    }

    private List<FlinkTableLineage> rows(String assetId, int version) {
        return lineage.selectList(Wrappers.<FlinkTableLineage>lambdaQuery()
                .eq(FlinkTableLineage::getAssetId, assetId)
                .eq(FlinkTableLineage::getAssetVersion, version)
                .orderByAsc(FlinkTableLineage::getSourceName));
    }

    private List<FlinkTableLineage> store(String projectId, String assetId, int version, String runId) {
        var job = studio.detail(projectId, assetId, version);
        if (job.kind() != FlinkAsset.Kind.JOB) {
            return List.of();
        }
        var parsed = FlinkSqlLineage.parse(studio.exportSql(projectId, job));
        if (parsed.empty()) {
            return List.of();
        }
        Map<String, String> cache = new HashMap<>();
        String targetAsset = resolve(parsed.sinkAssetHint(), parsed.sinkConnector(),
                parsed.sinkPhysicalName(), parsed.sink(), cache);
        List<FlinkTableLineage> rows = new ArrayList<>();
        for (FlinkSqlLineage.Source source : parsed.sources()) {
            FlinkTableLineage row = new FlinkTableLineage();
            row.setProjectId(projectId);
            row.setAssetId(assetId);
            row.setAssetVersion(version);
            row.setRunId(runId);
            row.setSourceName(source.name());
            row.setSourceAssetId(resolve(source.assetHint(), source.connector(),
                    source.physicalName(), source.name(), cache));
            row.setTargetName(parsed.sink());
            row.setTargetAssetId(targetAsset);
            row.setRelationType("FLINK");
            rows.add(row);
        }
        lineage.delete(Wrappers.<FlinkTableLineage>lambdaQuery()
                .eq(FlinkTableLineage::getAssetId, assetId)
                .eq(FlinkTableLineage::getAssetVersion, version));
        rows.forEach(lineage::insert);
        return rows;
    }

    /** 只回写两端都有资产的血缘；调用失败已在客户端降级，不影响解析结果。 */
    private void mirror(List<FlinkTableLineage> rows) {
        Set<String> written = new LinkedHashSet<>();
        for (FlinkTableLineage row : rows) {
            String source = row.getSourceAssetId();
            String target = row.getTargetAssetId();
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            if (written.add(source + "->" + target)) {
                metadata.saveLineage(source, target);
            }
        }
    }

    /**
     * 表名解析优先级：DDL 显式 asset-id > 物理表名（jdbc/cdc）> 逻辑名。
     * Kafka/Pulsar 只认显式 asset-id，避免把 topic 误认成同名表。
     */
    private String resolve(String hint, String connector, String physical, String logical,
                           Map<String, String> cache) {
        if (hint != null && ASSET_ID.matcher(hint).matches()) {
            return hint;
        }
        String name = physical == null || physical.isBlank() ? logical : physical;
        if (name == null || name.isBlank()) {
            return null;
        }
        if (connector != null && TOPIC_CONNECTORS.contains(connector.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (name.matches(".*[\\\\*?+\\[\\]()|].*")) {
            // 正则表名（如 orders_.*）无法唯一对应一个资产，等显式 asset-id。
            return null;
        }
        if (cache.containsKey(name)) {
            String cached = cache.get(name);
            return cached.isEmpty() ? null : cached;
        }
        String database = null;
        String table = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            database = name.substring(0, dot);
            table = name.substring(dot + 1);
        }
        String id = metadata.findAsset(table, database);
        cache.put(name, id == null ? "" : id);
        return id;
    }

    private View toView(String assetId, int version, List<FlinkTableLineage> rows) {
        if (rows.isEmpty()) {
            return new View(assetId, version, null, List.of(), List.of());
        }
        String sink = rows.get(0).getTargetName();
        String sinkAsset = rows.get(0).getTargetAssetId();
        Map<String, Node> nodes = new LinkedHashMap<>();
        if (sink != null) {
            nodes.put("sink:" + sink, new Node(sink, sinkAsset, null, null, "SINK"));
        }
        List<Edge> edges = new ArrayList<>();
        for (FlinkTableLineage row : rows) {
            String key = "source:" + row.getSourceName();
            nodes.putIfAbsent(key, new Node(row.getSourceName(), row.getSourceAssetId(), null, null, "SOURCE"));
            edges.add(new Edge(row.getSourceName(), row.getTargetName()));
        }
        return new View(assetId, version, sink, List.copyOf(nodes.values()), edges);
    }
}
