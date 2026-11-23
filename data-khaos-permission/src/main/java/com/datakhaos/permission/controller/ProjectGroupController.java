package com.datakhaos.permission.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.permission.api.model.ProjectGroupDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceResourceDto;
import com.datakhaos.permission.entity.SgProjectGroup;
import com.datakhaos.permission.entity.SgProjectGroupResource;
import com.datakhaos.permission.entity.SgProjectRole;
import com.datakhaos.permission.service.ProjectGroupService;
import com.datakhaos.permission.service.ProjectGroupAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目组权限管理：组织(业务线) → 项目组 → 人 三级模型。
 * 人加入项目组即获得组内角色能力位（操作权限）与组下资源（数据权限）。
 */
@Tag(name = "项目组权限")
@RestController
@RequestMapping("/api/permission/project-group")
@RequiredArgsConstructor
public class ProjectGroupController {

    private final ProjectGroupService projectGroupService;
    private final ProjectGroupAccess access;

    @GetMapping("/me")
    @Operation(summary = "查询登录用户可访问的项目空间")
    public R<List<ProjectWorkspaceDto>> myWorkspaces() {
        return R.ok(projectGroupService.getUserProjectGroups(access.actorId()).stream()
                .map(ProjectWorkspaceDto::of).toList());
    }

    @GetMapping("/{id}/workspace")
    @Operation(summary = "查看当前成员在指定项目的角色与能力")
    public R<ProjectWorkspaceDto> workspace(@PathVariable String id) {
        return R.ok(ProjectWorkspaceDto.of(access.requireMembership(id)));
    }

    @GetMapping("/{id}/workspace/resources")
    public R<List<ProjectWorkspaceResourceDto>> workspaceResources(@PathVariable String id) {
        access.requireMembership(id);
        return R.ok(projectGroupService.listWorkspaceResources(id));
    }

    @GetMapping("/{id}/assignable-roles")
    @Operation(summary = "列出项目可分配的成员角色")
    public R<List<SgProjectRole>> assignableRoles(@PathVariable String id) {
        access.requireManagement(id);
        return R.ok(projectGroupService.assignableRoles(id));
    }

    private List<String> allowedGroupIds() {
        String actor = access.actorId();
        return access.isPlatformAdmin(actor) ? null : projectGroupService.getUserProjectGroups(actor).stream()
                .map(ProjectGroupDto::getId).toList();
    }

    // ---------- 项目组 ----------

    @Operation(summary = "分页查询项目组")
    @GetMapping("/page")
    public R<PageResult<SgProjectGroup>> page(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String orgId,
                                              @RequestParam(required = false) String keyword) {
        return R.ok(projectGroupService.page(current, size, orgId, keyword, allowedGroupIds()));
    }

    @Operation(summary = "查询组织下全部项目组")
    @GetMapping("/list")
    public R<List<SgProjectGroup>> list(@RequestParam(required = false) String orgId) {
        return R.ok(projectGroupService.list(orgId, allowedGroupIds()));
    }

    @Operation(summary = "新增项目组")
    @PostMapping
    public R<Void> create(@RequestBody SgProjectGroup group) {
        access.requirePlatformAdmin();
        projectGroupService.save(group);
        return R.ok();
    }

    @Operation(summary = "更新项目组")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable String id, @RequestBody SgProjectGroup group) {
        access.requirePlatformAdmin();
        group.setId(id);
        projectGroupService.update(group);
        return R.ok();
    }

    @Operation(summary = "删除项目组")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        access.requirePlatformAdmin();
        projectGroupService.delete(id);
        return R.ok();
    }

    // ---------- 成员 ----------

    @Operation(summary = "查询项目组成员（含组内角色与能力位）")
    @GetMapping("/{id}/members")
    public R<List<Map<String, Object>>> members(@PathVariable String id) {
        access.requireManagement(id);
        return R.ok(projectGroupService.listMembers(id));
    }

    @Operation(summary = "设置项目组成员（全量替换）")
    @PutMapping("/{id}/members")
    public R<Void> assignMembers(@PathVariable String id,
                                 @RequestBody List<ProjectGroupService.MemberAssign> assigns) {
        String actor = access.actorId();
        access.requirePlatformAdmin();
        projectGroupService.assignMembers(id, assigns, actor);
        return R.ok();
    }

    @PutMapping("/{id}/members/{userId}")
    public R<Void> setMember(@PathVariable String id, @PathVariable String userId, @RequestBody MemberRole body) {
        String actor = access.actorId();
        projectGroupService.setMember(id, userId, body.projectRoleId(), body.primary(), actor, access.isPlatformAdmin(actor));
        return R.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public R<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
        String actor = access.actorId();
        projectGroupService.removeMember(id, userId, actor, access.isPlatformAdmin(actor));
        return R.ok();
    }

    public record MemberRole(String projectRoleId, Boolean primary) {}

    // ---------- 角色/能力位 ----------

    @Operation(summary = "分页查询项目组角色")
    @GetMapping("/{id}/roles/page")
    public R<PageResult<SgProjectRole>> rolePage(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @PathVariable String id) {
        access.requireManagement(id);
        return R.ok(projectGroupService.rolePage(current, size, id));
    }

    @Operation(summary = "新增项目组角色")
    @PostMapping("/role")
    public R<Void> createRole(@RequestBody SgProjectRole role) {
        access.requirePlatformAdmin();
        projectGroupService.saveRole(role);
        return R.ok();
    }

    @Operation(summary = "更新项目组角色")
    @PutMapping("/role/{id}")
    public R<Void> updateRole(@PathVariable String id, @RequestBody SgProjectRole role) {
        access.requirePlatformAdmin();
        role.setId(id);
        projectGroupService.updateRole(role);
        return R.ok();
    }

    @Operation(summary = "删除项目组角色")
    @DeleteMapping("/role/{id}")
    public R<Void> deleteRole(@PathVariable String id) {
        access.requirePlatformAdmin();
        projectGroupService.deleteRole(id);
        return R.ok();
    }

    // ---------- 资源 ----------

    @Operation(summary = "查询项目组资源")
    @GetMapping("/{id}/resources")
    public R<List<SgProjectGroupResource>> resources(@PathVariable String id) {
        access.requireRead(id);
        return R.ok(projectGroupService.listResources(id));
    }

    @Operation(summary = "绑定项目组资源")
    @PostMapping("/{id}/resources")
    public R<Void> bindResources(@PathVariable String id,
                                 @RequestBody List<SgProjectGroupResource> resources) {
        access.requirePlatformAdmin();
        projectGroupService.bindResources(id, resources);
        return R.ok();
    }

    @Operation(summary = "删除项目组资源")
    @DeleteMapping("/resource/{id}")
    public R<Void> deleteResource(@PathVariable String id) {
        access.requirePlatformAdmin();
        projectGroupService.deleteResource(id);
        return R.ok();
    }

    // ---------- 权限下发 ----------

    @Operation(summary = "查询用户加入的项目组（含角色与能力位）")
    @GetMapping("/user/{userId}")
    public R<List<ProjectGroupDto>> userGroups(@PathVariable String userId) {
        access.requireSelfOrAdmin(userId);
        return R.ok(projectGroupService.getUserProjectGroups(userId));
    }

    @Operation(summary = "查询用户当前项目组（主组）")
    @GetMapping("/user/{userId}/current")
    public R<ProjectGroupDto> currentGroup(@PathVariable String userId) {
        access.requireSelfOrAdmin(userId);
        return R.ok(projectGroupService.getCurrentProjectGroup(userId));
    }

    @Operation(summary = "查询用户在项目组内的能力位")
    @GetMapping("/user/{userId}/capability")
    public R<List<String>> userCapability(@PathVariable String userId,
                                          @RequestParam(required = false) String projectGroupId) {
        access.requireSelfOrAdmin(userId);
        return R.ok(projectGroupService.getCapabilityFlags(userId, projectGroupId));
    }
}