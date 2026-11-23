package com.datakhaos.permission.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.permission.api.model.ProjectGroupDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceResourceDto;
import com.datakhaos.permission.entity.SgProjectGroup;
import com.datakhaos.permission.entity.SgProjectGroupMember;
import com.datakhaos.permission.entity.SgProjectGroupResource;
import com.datakhaos.permission.entity.SgProjectRole;
import com.datakhaos.permission.mapper.SgProjectGroupMapper;
import com.datakhaos.permission.mapper.SgProjectGroupMemberMapper;
import com.datakhaos.permission.mapper.SgProjectGroupResourceMapper;
import com.datakhaos.permission.mapper.SgProjectRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目组权限服务：组织(业务线) → 项目组 → 人 三级模型。
 * 人加入项目组即获得组内角色能力位（操作权限）与组下资源（数据权限）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupService {

    private final SgProjectGroupMapper groupMapper;
    private final SgProjectGroupMemberMapper memberMapper;
    private final SgProjectRoleMapper roleMapper;
    private final SgProjectGroupResourceMapper resourceMapper;

    public boolean isActiveUser(String userId) {
        return memberMapper.countActiveUser(userId) > 0;
    }

    // ---------- 项目组 CRUD ----------

    public PageResult<SgProjectGroup> page(long current, long size, String orgId, String keyword, List<String> allowedIds) {
        if (allowedIds != null && allowedIds.isEmpty()) {
            return PageResult.of(current, size, 0, List.of());
        }
        LambdaQueryWrapper<SgProjectGroup> wrapper = new LambdaQueryWrapper<SgProjectGroup>()
                .eq(StrUtil.isNotBlank(orgId), SgProjectGroup::getOrgId, orgId)
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(SgProjectGroup::getProjectName, keyword)
                        .or().like(SgProjectGroup::getProjectCode, keyword))
                .orderByAsc(SgProjectGroup::getSortOrder);
        if (allowedIds != null) {
            wrapper.in(SgProjectGroup::getId, allowedIds);
        }
        var result = groupMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(current, size), wrapper);
        return PageResult.of(current, size, result.getTotal(), result.getRecords());
    }

    public List<SgProjectGroup> list(String orgId, List<String> allowedIds) {
        if (allowedIds != null && allowedIds.isEmpty()) {
            return List.of();
        }
        var query = new LambdaQueryWrapper<SgProjectGroup>()
                .eq(StrUtil.isNotBlank(orgId), SgProjectGroup::getOrgId, orgId)
                .orderByAsc(SgProjectGroup::getSortOrder);
        if (allowedIds != null) {
            query.in(SgProjectGroup::getId, allowedIds);
        }
        return groupMapper.selectList(query);
    }

    public void save(SgProjectGroup group) {
        if (group.getStatus() == null) {
            group.setStatus(1);
        }
        groupMapper.insert(group);
    }

    public void update(SgProjectGroup group) {
        groupMapper.updateById(group);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Long members = memberMapper.selectCount(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getProjectGroupId, id));
        if (members != null && members > 0) {
            throw new BusinessException("项目组下仍有成员，无法删除");
        }
        groupMapper.deleteById(id);
        roleMapper.delete(new LambdaQueryWrapper<SgProjectRole>().eq(SgProjectRole::getProjectGroupId, id));
        resourceMapper.delete(new LambdaQueryWrapper<SgProjectGroupResource>()
                .eq(SgProjectGroupResource::getProjectGroupId, id));
    }

    // ---------- 成员管理 ----------

    public List<Map<String, Object>> listMembers(String projectGroupId) {
        List<SgProjectGroupMember> members = memberMapper.selectList(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getProjectGroupId, projectGroupId)
                .orderByDesc(SgProjectGroupMember::getIsPrimary));
        if (members.isEmpty()) {
            return List.of();
        }
        List<String> roleIds = members.stream().map(SgProjectGroupMember::getProjectRoleId)
                .filter(StrUtil::isNotBlank).distinct().toList();
        Map<String, SgProjectRole> roleMap = roleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(SgProjectRole::getId, r -> r));
        List<Map<String, Object>> result = new ArrayList<>();
        for (SgProjectGroupMember m : members) {
            SgProjectRole role = m.getProjectRoleId() == null ? null : roleMap.get(m.getProjectRoleId());
            result.add(Map.of(
                    "memberId", m.getId(),
                    "userId", m.getUserId(),
                    "projectRoleId", m.getProjectRoleId() == null ? "" : m.getProjectRoleId(),
                    "roleCode", role == null ? "" : role.getRoleCode(),
                    "roleName", role == null ? "" : role.getRoleName(),
                    "capabilityFlags", role == null ? List.of() : parseFlags(role.getCapabilityFlags()),
                    "primary", m.getIsPrimary() != null && m.getIsPrimary() == 1));
        }
        return result;
    }

    /** 在同一事务校验并替换成员。 */
    @Transactional(rollbackFor = Exception.class)
    public void assignMembers(String projectGroupId, List<MemberAssign> assigns, String actorId) {
        SgProjectGroup group = lockManagedGroup(projectGroupId, actorId, true);
        if (assigns == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "成员列表不能为空");
        }
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> roleIds = new LinkedHashSet<>();
        for (MemberAssign assignment : assigns) {
            if (assignment == null || StrUtil.isBlank(assignment.userId()) || StrUtil.isBlank(assignment.projectRoleId())
                    || !userIds.add(assignment.userId())) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "请提供唯一的有效成员和角色");
            }
            if (memberMapper.countActiveUser(assignment.userId()) == 0
                    && selectMember(projectGroupId, assignment.userId()) == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "成员用户不存在或已停用");
            }
            roleIds.add(assignment.projectRoleId());
        }
        Map<String, SgProjectRole> roles = roleIds.isEmpty() ? Map.of() : roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> isEligibleRole(group, role)).collect(Collectors.toMap(SgProjectRole::getId, role -> role));
        if (roles.size() != roleIds.size()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "成员角色未启用或不属于当前项目");
        }
        memberMapper.delete(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getProjectGroupId, projectGroupId));
        for (MemberAssign assignment : assigns) {
            SgProjectGroupMember member = new SgProjectGroupMember();
            member.setProjectGroupId(projectGroupId);
            member.setUserId(assignment.userId());
            member.setProjectRoleId(assignment.projectRoleId());
            member.setIsPrimary(Boolean.TRUE.equals(assignment.primary()) ? 1 : 0);
            memberMapper.insert(member);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setMember(String projectId, String userId, String roleId, Boolean primary,
                          String actorId, boolean platformAdmin) {
        SgProjectGroup group = lockManagedGroup(projectId, actorId, platformAdmin);
        SgProjectRole role = StrUtil.isBlank(roleId) ? null : roleMapper.selectById(roleId);
        if (!isEligibleRole(group, role)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "成员角色未启用或不属于当前项目");
        }
        if (!platformAdmin && actorId.equals(userId) && !parseFlags(role.getCapabilityFlags()).contains("pg:manage")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "项目管理者不能移除自身管理权限");
        }
        SgProjectGroupMember member = selectMember(projectId, userId);
        if (member == null) {
            if (memberMapper.countActiveUser(userId) == 0) {
                throw new BusinessException(ResultCode.NOT_FOUND, "成员用户不存在或已停用");
            }
            member = new SgProjectGroupMember();
            member.setProjectGroupId(projectId);
            member.setUserId(userId);
        }
        member.setProjectRoleId(roleId);
        if (platformAdmin && primary != null) {
            member.setIsPrimary(primary ? 1 : 0);
        }
        if (member.getId() == null) {
            memberMapper.insert(member);
        } else {
            memberMapper.updateById(member);
        }
        log.info("项目成员设置 projectId={} memberUserId={} roleId={} actorId={}", projectId, userId, roleId, actorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String projectId, String userId, String actorId, boolean platformAdmin) {
        lockManagedGroup(projectId, actorId, platformAdmin);
        if (!platformAdmin && actorId.equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "项目管理者不能移除自身成员身份");
        }
        memberMapper.delete(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getProjectGroupId, projectId).eq(SgProjectGroupMember::getUserId, userId));
        log.info("项目成员移除 projectId={} memberUserId={} actorId={}", projectId, userId, actorId);
    }

    private SgProjectGroupMember selectMember(String projectId, String userId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getProjectGroupId, projectId).eq(SgProjectGroupMember::getUserId, userId));
    }

    private SgProjectGroup lockManagedGroup(String projectGroupId, String actorId, boolean platformAdmin) {
        SgProjectGroup group = groupMapper.selectOne(new LambdaQueryWrapper<SgProjectGroup>()
                .eq(SgProjectGroup::getId, projectGroupId).last("FOR UPDATE"));
        if (group == null || !Integer.valueOf(1).equals(group.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "项目不存在或未启用");
        }
        if (!platformAdmin && !getCapabilityFlags(actorId, projectGroupId).contains("pg:manage")) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return group;
    }

    public List<SgProjectRole> assignableRoles(String projectGroupId) {
        SgProjectGroup group = groupMapper.selectById(projectGroupId);
        if (group == null || !Integer.valueOf(1).equals(group.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "项目不存在或未启用");
        }
        return roleMapper.selectList(new LambdaQueryWrapper<SgProjectRole>()
                        .eq(SgProjectRole::getStatus, 1)
                        .and(query -> query.eq(SgProjectRole::getProjectGroupId, projectGroupId)
                                .or().isNull(SgProjectRole::getProjectGroupId))
                        .orderByAsc(SgProjectRole::getSortOrder, SgProjectRole::getId))
                .stream().filter(role -> isEligibleRole(group, role)).toList();
    }

    private boolean isEligibleRole(SgProjectGroup group, SgProjectRole role) {
        return role != null && Integer.valueOf(1).equals(role.getStatus())
                && (role.getOrgId() == null || Objects.equals(role.getOrgId(), group.getOrgId()))
                && (role.getProjectGroupId() == null || Objects.equals(role.getProjectGroupId(), group.getId()));
    }

    // ---------- 角色 CRUD（能力位） ----------

    public PageResult<SgProjectRole> rolePage(long current, long size, String projectGroupId) {
        LambdaQueryWrapper<SgProjectRole> wrapper = new LambdaQueryWrapper<SgProjectRole>()
                .eq(StrUtil.isNotBlank(projectGroupId), SgProjectRole::getProjectGroupId, projectGroupId)
                .orderByAsc(SgProjectRole::getSortOrder);
        var result = roleMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(current, size), wrapper);
        return PageResult.of(current, size, result.getTotal(), result.getRecords());
    }

    public void saveRole(SgProjectRole role) {
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        roleMapper.insert(role);
    }

    public void updateRole(SgProjectRole role) {
        roleMapper.updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String id) {
        Long used = memberMapper.selectCount(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getProjectRoleId, id));
        if (used != null && used > 0) {
            throw new BusinessException("角色正被成员使用，无法删除");
        }
        roleMapper.deleteById(id);
    }

    // ---------- 资源绑定 ----------

    public List<SgProjectGroupResource> listResources(String projectGroupId) {
        return resourceMapper.selectList(new LambdaQueryWrapper<SgProjectGroupResource>()
                .eq(StrUtil.isNotBlank(projectGroupId), SgProjectGroupResource::getProjectGroupId, projectGroupId)
                .orderByAsc(SgProjectGroupResource::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindResources(String projectGroupId, List<SgProjectGroupResource> resources) {
        if (resources == null) {
            return;
        }
        SgProjectGroup group = groupMapper.selectById(projectGroupId);
        if (group == null || !Integer.valueOf(1).equals(group.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "项目不存在或未启用");
        }
        for (SgProjectGroupResource input : resources) {
            if (input == null || StrUtil.isBlank(input.getResourceType()) || StrUtil.isBlank(input.getResourceId())) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "资源类型和 ID 不能为空");
            }
            String type = input.getResourceType().trim().toUpperCase(java.util.Locale.ROOT);
            String resourceId = input.getResourceId().trim();
            if ("DATASOURCE".equals(type) && resourceMapper.countAvailableDatasource(resourceId) == 0) {
                throw new BusinessException(ResultCode.NOT_FOUND, "数据源不存在或未启用");
            }
            SgProjectGroupResource binding = new SgProjectGroupResource();
            binding.setProjectGroupId(projectGroupId);
            binding.setResourceType(type);
            binding.setResourceId(resourceId);
            resourceMapper.insert(binding);
        }
    }

    public List<ProjectWorkspaceResourceDto> listWorkspaceResources(String projectId) {
        return resourceMapper.listWorkspaceResources(projectId);
    }

    public void deleteResource(String id) {
        resourceMapper.deleteById(id);
    }

    // ---------- 权限下发：用户项目组视图 + 能力位合并 ----------

    /**
     * 查询用户加入的项目组（含组内角色与能力位），并标记主项目组。
     */
    public List<ProjectGroupDto> getUserProjectGroups(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }
        List<SgProjectGroupMember> members = memberMapper.selectList(new LambdaQueryWrapper<SgProjectGroupMember>()
                .eq(SgProjectGroupMember::getUserId, userId));
        if (members.isEmpty()) {
            return List.of();
        }
        List<String> groupIds = members.stream().map(SgProjectGroupMember::getProjectGroupId).distinct().toList();
        Map<String, SgProjectGroup> groupMap = groupMapper.selectBatchIds(groupIds).stream()
                .filter(g -> Integer.valueOf(1).equals(g.getStatus()))
                .collect(Collectors.toMap(SgProjectGroup::getId, g -> g));
        List<String> roleIds = members.stream().map(SgProjectGroupMember::getProjectRoleId)
                .filter(StrUtil::isNotBlank).distinct().toList();
        Map<String, SgProjectRole> roleMap = roleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(SgProjectRole::getId, r -> r));

        List<ProjectGroupDto> result = new ArrayList<>();
        for (SgProjectGroupMember m : members) {
            SgProjectGroup group = groupMap.get(m.getProjectGroupId());
            if (group == null) {
                continue;
            }
            SgProjectRole role = m.getProjectRoleId() == null ? null : roleMap.get(m.getProjectRoleId());
            ProjectGroupDto dto = new ProjectGroupDto();
            dto.setId(group.getId());
            dto.setOrgId(group.getOrgId());
            dto.setProjectName(group.getProjectName());
            dto.setProjectCode(group.getProjectCode());
            dto.setLeaderId(group.getLeaderId());
            dto.setPrimary(m.getIsPrimary() != null && m.getIsPrimary() == 1);
            if (isEligibleRole(group, role)) {
                dto.setRoleCode(role.getRoleCode());
                dto.setCapabilityFlags(parseFlags(role.getCapabilityFlags()));
            }
            result.add(dto);
        }
        result.sort(java.util.Comparator.comparing(ProjectGroupDto::getPrimary).reversed()
                .thenComparing(ProjectGroupDto::getId));
        return result;
    }

    /**
     * 取用户当前项目组（主项目组；无主组时取第一个）。
     */
    public ProjectGroupDto getCurrentProjectGroup(String userId) {
        List<ProjectGroupDto> groups = getUserProjectGroups(userId);
        return groups.stream().filter(ProjectGroupDto::getPrimary).findFirst()
                .orElse(groups.isEmpty() ? null : groups.get(0));
    }

    /**
     * 合并用户在指定项目组内的能力位（当前组角色的能力位集合）。
     */
    public List<String> getCapabilityFlags(String userId, String projectGroupId) {
        return getUserProjectGroups(userId).stream()
                .filter(group -> StrUtil.isBlank(projectGroupId) || projectGroupId.equals(group.getId()))
                .findFirst().map(ProjectGroupDto::getCapabilityFlags).orElseGet(List::of);
    }

    /** 解析能力位 JSON 数组字符串为去重、保序列表 */
    private List<String> parseFlags(String json) {
        Set<String> set = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(json)) {
            try {
                for (Object o : JSONUtil.parseArray(json)) {
                    set.add(String.valueOf(o));
                }
            } catch (Exception e) {
                log.warn("解析能力位失败: {}", json);
            }
        }
        return new ArrayList<>(set);
    }

    /** 成员分配入参 */
    public record MemberAssign(String userId, String projectRoleId, Boolean primary) {
    }
}