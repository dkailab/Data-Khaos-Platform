package com.datakhaos.workflow.executor;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL 节点执行器：通过数据源服务执行 SQL（走统一 SQL 审核），返回影响/结果行数。
 *
 * <p>支持两种执行模式（节点配置 {@code sqlSplitMode}）：
 * <ul>
 *   <li>{@code NONE}（默认）：整段 SQL 一次提交；</li>
 *   <li>{@code SPLIT}：按分号拆成多条顺序执行，任一失败立即中止并报告第几条。</li>
 * </ul>
 * 外部 SQL 文件的来源说明会写入执行日志，便于回溯本次跑的是草稿还是已发布版本。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlNodeExecutor implements NodeExecutor {

    public static final String CONFIG_SPLIT_MODE = "sqlSplitMode";
    public static final String SPLIT_MODE_SPLIT = "SPLIT";

    private static final int MAX_LOG_CHARS = 4000;

    private final DatasourceApiClient datasourceApiClient;

    @Override
    public String getType() {
        return NodeType.SQL;
    }

    @Override
    public ExecResult execute(NodeContext context) {
        String sql = context.getContent();
        String dsId = context.getDatasourceId();
        if (StrUtil.isBlank(sql)) {
            throw new IllegalArgumentException("SQL 节点内容为空");
        }
        if (dsId == null || dsId.isBlank()) {
            throw new IllegalArgumentException("SQL 节点未指定数据源");
        }
        boolean split = context.getConfig() != null
                && SPLIT_MODE_SPLIT.equalsIgnoreCase(String.valueOf(context.getConfig().get(CONFIG_SPLIT_MODE)));
        List<String> statements = split ? SqlScriptSplitter.split(sql) : List.of(sql);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("SQL 拆分后没有可执行语句");
        }

        long start = System.currentTimeMillis();
        StringBuilder logText = new StringBuilder();
        Object noteValue = context.getConfig() == null ? null : context.getConfig().get("__sqlSourceNote");
        String sourceNote = noteValue == null ? null : String.valueOf(noteValue);
        if (StrUtil.isNotBlank(sourceNote)) {
            logText.append(sourceNote).append('\n');
        }
        int totalRows = 0;
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            R<QueryResult> response;
            try {
                response = datasourceApiClient.executeRaw(dsId, statement);
            } catch (RuntimeException e) {
                throw new IllegalStateException(prefix(statements.size(), i) + "提交失败: " + e.getMessage(), e);
            }
            if (response == null || response.getCode() != 0) {
                String message = response == null ? "无响应" : response.getMsg();
                throw new IllegalStateException(prefix(statements.size(), i) + "执行失败: " + message);
            }
            QueryResult data = response.getData();
            int rows = data == null || data.getRowCount() == null ? 0 : data.getRowCount();
            totalRows += rows;
            if (statements.size() > 1) {
                logText.append(prefix(statements.size(), i)).append("执行成功，行数 ").append(rows).append('\n');
            }
        }
        long cost = System.currentTimeMillis() - start;
        logText.append("SQL 执行成功，共 ").append(statements.size()).append(" 条语句，耗时 ")
                .append(cost).append("ms，累计影响/结果行数 ").append(totalRows);
        return ExecResult.builder()
                .success(true)
                .rows(totalRows)
                .log(truncate(logText.toString()))
                .build();
    }

    private static String prefix(int total, int index) {
        return total > 1 ? "第 " + (index + 1) + "/" + total + " 条" : "SQL";
    }

    private static String truncate(String text) {
        return text.length() <= MAX_LOG_CHARS ? text : text.substring(0, MAX_LOG_CHARS) + "...";
    }
}
