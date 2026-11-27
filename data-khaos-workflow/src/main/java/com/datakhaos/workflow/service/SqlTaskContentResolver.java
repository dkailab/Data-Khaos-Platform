package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowSqlFile;
import com.datakhaos.workflow.mapper.WorkflowSqlFileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 解析 SQL 节点的执行内容：支持「自定义任务」直接写 SQL，也支持「外部任务」引用已保存的 SQL 文件。
 *
 * <p>外部任务读取规则：
 * <ul>
 *   <li>执行对象 {@code DRAFT}（默认）：读取文件草稿内容；</li>
 *   <li>执行对象 {@code LATEST}：读取已发布快照；文件从未发布时回退草稿，避免节点无内容可跑；</li>
 *   <li>节点未配置数据源时继承文件的数据源。</li>
 * </ul>
 * 文件被删除或内容为空时直接报错，不静默跳过——调度节点空转比失败更难排查。</p>
 */
@Component
@RequiredArgsConstructor
public class SqlTaskContentResolver {

    public static final String MODE_CUSTOM = "CUSTOM";
    public static final String MODE_EXTERNAL = "EXTERNAL";
    public static final String OBJECT_DRAFT = "DRAFT";
    public static final String OBJECT_LATEST = "LATEST";

    private final WorkflowSqlFileMapper sqlFileMapper;

    public ResolvedSql resolve(Map<String, Object> config, String datasourceId) {
        String mode = text(config.get("taskMode"));
        if (!MODE_EXTERNAL.equalsIgnoreCase(mode)) {
            return new ResolvedSql(text(config.get("sql")), datasourceId, null);
        }
        String fileId = text(config.get("sqlFileId"));
        if (StrUtil.isBlank(fileId)) {
            throw new BusinessException("外部任务未选择 SQL 文件");
        }
        WorkflowSqlFile file = sqlFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException("引用的 SQL 文件不存在或已删除: " + fileId);
        }
        boolean latest = OBJECT_LATEST.equalsIgnoreCase(text(config.get("externalObject")));
        boolean hasPublished = file.getPublishedVersion() != null && file.getPublishedVersion() > 0;
        String content = latest && hasPublished ? file.getPublishedContent() : file.getDraftContent();
        if (StrUtil.isBlank(content)) {
            throw new BusinessException("SQL 文件内容为空: " + file.getName()
                    + (latest && hasPublished ? "（已发布版本）" : "（草稿）"));
        }
        String effectiveDatasource = StrUtil.isBlank(datasourceId) ? file.getDatasourceId() : datasourceId;
        String source = "引用 SQL 文件「" + file.getName() + "」"
                + (latest ? (hasPublished ? "已发布 v" + file.getPublishedVersion() : "未发布，回退草稿 v" + file.getDraftVersion())
                          : "草稿 v" + file.getDraftVersion());
        return new ResolvedSql(content, effectiveDatasource, source);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * @param content      解析出的 SQL
     * @param datasourceId 生效的数据源（节点优先，其次文件）
     * @param sourceNote   来源说明，写入执行日志便于追溯
     */
    public record ResolvedSql(String content, String datasourceId, String sourceNote) {
    }
}
