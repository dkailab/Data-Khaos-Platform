package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.dto.WorkflowSqlFileRequest;
import com.datakhaos.workflow.entity.WorkflowSqlFile;
import com.datakhaos.workflow.mapper.WorkflowSqlFileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL 文件资源管理：数据开发工作台里可复用的 SQL，支持草稿与发布两态。
 *
 * <p>权限：读写都需要项目的 {@code task:develop} 能力；文件归属项目不可通过保存接口迁移。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkflowSqlFileService {

    private static final String DEVELOP = "task:develop";
    private static final int MAX_NAME = 200;
    private static final int MAX_FOLDER = 200;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_CONTENT_CHARS = 1_000_000;
    private static final int MAX_LIST = 500;

    private final WorkflowSqlFileMapper mapper;
    private final WorkflowProjectAccess access;

    /** 列出项目下的 SQL 文件（最多 500 条），keyword 同时匹配名称与目录。 */
    public List<WorkflowSqlFile> list(String projectId, String keyword) {
        access.requireAction(projectId, DEVELOP);
        LambdaQueryWrapper<WorkflowSqlFile> query = new LambdaQueryWrapper<>();
        if (StrUtil.isBlank(projectId)) {
            query.isNull(WorkflowSqlFile::getProjectId);
        } else {
            query.eq(WorkflowSqlFile::getProjectId, projectId.trim());
        }
        if (StrUtil.isNotBlank(keyword)) {
            String text = keyword.trim();
            query.and(w -> w.like(WorkflowSqlFile::getName, text).or().like(WorkflowSqlFile::getFolder, text));
        }
        // 列表不返回正文：SQL 文件可达 1MB，500 条一起返回会把资源树接口撑爆；
        // 编辑器/预览按 id 单独读取详情。
        query.select(WorkflowSqlFile::getId, WorkflowSqlFile::getProjectId, WorkflowSqlFile::getFolder,
                        WorkflowSqlFile::getName, WorkflowSqlFile::getDescription,
                        WorkflowSqlFile::getDatasourceId, WorkflowSqlFile::getDraftVersion,
                        WorkflowSqlFile::getPublishedVersion, WorkflowSqlFile::getUpdatedBy,
                        WorkflowSqlFile::getUpdateTime, WorkflowSqlFile::getCreateTime)
                .orderByAsc(WorkflowSqlFile::getFolder).orderByAsc(WorkflowSqlFile::getName)
                .last("LIMIT " + MAX_LIST);
        return mapper.selectList(query);
    }

    public WorkflowSqlFile get(String id) {
        WorkflowSqlFile file = require(id);
        access.requireAction(file.getProjectId(), DEVELOP);
        return file;
    }

    /** 新建或更新；更新时草稿版本号递增，已发布内容保持不变。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowSqlFile save(WorkflowSqlFileRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "缺少文件内容");
        }
        String name = StrUtil.trimToNull(request.name());
        if (name == null || name.length() > MAX_NAME) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "文件名必填且最多 " + MAX_NAME + " 字");
        }
        String folder = normalizeFolder(request.folder());
        String content = request.content() == null ? "" : request.content();
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "SQL 内容超过 100 万字符，请拆分文件");
        }
        String description = StrUtil.trimToNull(request.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "说明最多 " + MAX_DESCRIPTION + " 字");
        }

        if (StrUtil.isBlank(request.id())) {
            String projectId = StrUtil.trimToNull(request.projectId());
            access.requireAction(projectId, DEVELOP);
            WorkflowSqlFile file = new WorkflowSqlFile();
            file.setProjectId(projectId);
            file.setFolder(folder);
            file.setName(name);
            file.setDescription(description);
            file.setDatasourceId(StrUtil.trimToNull(request.datasourceId()));
            file.setDraftContent(content);
            file.setDraftVersion(1);
            file.setPublishedVersion(0);
            file.setUpdatedBy(MetadataHolder.getUserId());
            file.setCreateTime(LocalDateTime.now());
            file.setUpdateTime(LocalDateTime.now());
            mapper.insert(file);
            return file;
        }

        WorkflowSqlFile existing = require(request.id());
        access.requireAction(existing.getProjectId(), DEVELOP);
        if (StrUtil.isNotBlank(request.projectId())
                && !request.projectId().trim().equals(StrUtil.nullToEmpty(existing.getProjectId()))) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "文件归属项目不可变更");
        }
        existing.setFolder(folder);
        existing.setName(name);
        // 清空说明/数据源时写空串而不是 null：updateById 默认跳过 null，旧值会被保留
        existing.setDescription(description == null ? "" : description);
        existing.setDatasourceId(StrUtil.trimToNull(request.datasourceId()) == null
                ? "" : request.datasourceId().trim());
        existing.setDraftContent(content);
        existing.setDraftVersion(existing.getDraftVersion() == null ? 1 : existing.getDraftVersion() + 1);
        existing.setUpdatedBy(MetadataHolder.getUserId());
        existing.setUpdateTime(LocalDateTime.now());
        mapper.updateById(existing);
        return existing;
    }

    /** 发布：把当前草稿固化为已发布快照，外部任务选择「最新版本」时读取该快照。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowSqlFile publish(String id) {
        WorkflowSqlFile file = require(id);
        access.requireAction(file.getProjectId(), DEVELOP);
        if (StrUtil.isBlank(file.getDraftContent())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "草稿内容为空，无法发布");
        }
        file.setPublishedContent(file.getDraftContent());
        file.setPublishedVersion(file.getDraftVersion() == null ? 1 : file.getDraftVersion());
        file.setUpdatedBy(MetadataHolder.getUserId());
        file.setUpdateTime(LocalDateTime.now());
        mapper.updateById(file);
        return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        WorkflowSqlFile file = require(id);
        access.requireAction(file.getProjectId(), DEVELOP);
        mapper.deleteById(id);
    }

    private WorkflowSqlFile require(String id) {
        WorkflowSqlFile file = StrUtil.isBlank(id) ? null : mapper.selectById(id);
        if (file == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "SQL 文件不存在或已删除");
        }
        return file;
    }

    static String normalizeFolder(String raw) {
        String folder = StrUtil.trimToNull(raw);
        if (folder == null) {
            folder = "/SQL文件";
        }
        folder = folder.replace('\\', '/');
        if (!folder.startsWith("/")) {
            folder = "/" + folder;
        }
        while (folder.length() > 1 && folder.endsWith("/")) {
            folder = folder.substring(0, folder.length() - 1);
        }
        if (folder.length() > MAX_FOLDER) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "目录最多 " + MAX_FOLDER + " 字");
        }
        return folder;
    }
}
