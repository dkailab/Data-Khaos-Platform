package com.datakhaos.permission.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.permission.api.model.MenuDto;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.entity.SysMenu;
import com.datakhaos.permission.entity.SysRole;
import com.datakhaos.permission.entity.SysRolePermission;
import com.datakhaos.permission.entity.SysUserRole;
import com.datakhaos.permission.mapper.SysMenuMapper;
import com.datakhaos.permission.mapper.SysRoleMapper;
import com.datakhaos.permission.mapper.SysRolePermissionMapper;
import com.datakhaos.permission.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 权限核心服务：用户权限视图、角色权限绑定
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String DEFAULT_ADMIN_ID = "1";

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysMenuMapper menuMapper;
    private final com.datakhaos.permission.mapper.SysUserOrgMapper userOrgMapper;
    private final com.datakhaos.permission.mapper.SysOrgPermissionMapper orgPermissionMapper;
    private final ProjectGroupService projectGroupService;

    public boolean isPlatformAdministrator(String userId) {
        if (DEFAULT_ADMIN_ID.equals(userId)) {
            return true;
        }
        List<String> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)).stream().map(SysUserRole::getRoleId).toList();
        return !roleIds.isEmpty() && roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getId, roleIds).eq(SysRole::getRoleCode, SUPER_ADMIN).eq(SysRole::getStatus, 1)) > 0;
    }

    /**
     * 计算用户权限视图（角色 / 权限标识 / 可见菜单）
     */
    public UserPermissionDto getUserPermission(String userId) {
        UserPermissionDto dto = new UserPermissionDto();
        dto.setUserId(userId);

        List<String> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        List<SysRole> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectBatchIds(roleIds);
        List<String> roleCodes = roles.stream().map(SysRole::getRoleCode).toList();
        dto.setRoles(roleCodes);

        boolean superAdmin = roleCodes.contains(SUPER_ADMIN) || DEFAULT_ADMIN_ID.equals(userId);

        List<SysMenu> menus = superAdmin ? allMenus()
                : mergeRoleAndOrgMenus(roleIds, userId);

        dto.setMenus(menus.stream().map(this::toDto).toList());
        List<String> perms = new java.util.ArrayList<>(menus.stream().map(SysMenu::getPermission)
                .filter(StrUtil::isNotBlank).distinct().toList());

        // 项目组权限：用户加入的项目组 + 当前项目组上下文 + 能力位合并
        List<com.datakhaos.permission.api.model.ProjectGroupDto> groups = projectGroupService.getUserProjectGroups(userId);
        dto.setProjectGroups(groups);
        if (!groups.isEmpty()) {
            com.datakhaos.permission.api.model.ProjectGroupDto current = groups.stream()
                    .filter(com.datakhaos.permission.api.model.ProjectGroupDto::getPrimary).findFirst()
                    .orElse(groups.get(0));
            dto.setProjectGroupId(current.getId());
            List<String> capabilityFlags = new java.util.ArrayList<>(current.getCapabilityFlags());
            dto.setCapabilityFlags(capabilityFlags);
            // 能力位并入权限标识集合，供前端显隐菜单/按钮
            for (String flag : capabilityFlags) {
                if (!perms.contains(flag)) {
                    perms.add(flag);
                }
            }
        }
        dto.setPermissions(perms);
        // 超级管理员天然具备模块展示配置能力（即使无项目组上下文）
        if (superAdmin && !perms.contains(com.datakhaos.permission.api.service.PermissionConstants.CAP_MODULE_CONFIG)) {
            perms.add(com.datakhaos.permission.api.service.PermissionConstants.CAP_MODULE_CONFIG);
        }
        if (superAdmin && !dto.getCapabilityFlags().contains(com.datakhaos.permission.api.service.PermissionConstants.CAP_MODULE_CONFIG)) {
            dto.getCapabilityFlags().add(com.datakhaos.permission.api.service.PermissionConstants.CAP_MODULE_CONFIG);
        }
        // 超级管理员天然具备数据管道能力位
        String[] pipelineCaps = {
                com.datakhaos.permission.api.service.PermissionConstants.CAP_PIPELINE_MANAGE,
                com.datakhaos.permission.api.service.PermissionConstants.CAP_PIPELINE_BROWSE,
                com.datakhaos.permission.api.service.PermissionConstants.CAP_PIPELINE_RUN
        };
        if (superAdmin) {
            for (String cap : pipelineCaps) {
                if (!perms.contains(cap)) {
                    perms.add(cap);
                }
                if (!dto.getCapabilityFlags().contains(cap)) {
                    dto.getCapabilityFlags().add(cap);
                }
            }
        }
        return dto;
    }

    /** 合并角色菜单与所属部门继承的菜单权限 */
    private List<SysMenu> mergeRoleAndOrgMenus(List<String> roleIds, String userId) {
        List<String> menuIds = roleIds.isEmpty() ? List.of() : roleMenuIds(roleIds);
        // 用户所属部门授予的菜单权限（成员自动继承）
        List<String> orgIds = userOrgMapper.selectList(
                        new LambdaQueryWrapper<com.datakhaos.permission.entity.SysUserOrg>()
                                .eq(com.datakhaos.permission.entity.SysUserOrg::getUserId, userId))
                .stream().map(com.datakhaos.permission.entity.SysUserOrg::getOrgId).toList();
        if (!orgIds.isEmpty()) {
            List<String> orgMenuIds = orgPermissionMapper.selectList(
                            new LambdaQueryWrapper<com.datakhaos.permission.entity.SysOrgPermission>()
                                    .in(com.datakhaos.permission.entity.SysOrgPermission::getOrgId, orgIds)
                                    .eq(com.datakhaos.permission.entity.SysOrgPermission::getPermissionType, "MENU"))
                    .stream().map(com.datakhaos.permission.entity.SysOrgPermission::getPermissionId).distinct().toList();
            menuIds = new java.util.ArrayList<>(menuIds);
            for (String id : orgMenuIds) {
                if (!menuIds.contains(id)) {
                    menuIds.add(id);
                }
            }
        }
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectBatchIds(menuIds).stream()
                .filter(m -> m.getStatus() != null && m.getStatus() == 1)
                .sorted((a, b) -> {
                    int sa = a.getSortOrder() == null ? 0 : a.getSortOrder();
                    int sb = b.getSortOrder() == null ? 0 : b.getSortOrder();
                    return Integer.compare(sa, sb);
                }).toList();
    }

    private List<String> roleMenuIds(List<String> roleIds) {
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>()
                                .in(SysRolePermission::getRoleId, roleIds)
                                .eq(SysRolePermission::getPermissionType, "MENU"))
                .stream().map(SysRolePermission::getPermissionId).distinct().toList();
    }

    /** 查询用户权限标识集合 */
    public List<String> getUserPermissions(String userId) {
        return getUserPermission(userId).getPermissions();
    }

    /**
     * 绑定角色权限（全量替换菜单权限）
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRolePermissions(String roleId, List<String> menuIds) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        if (menuIds != null) {
            for (String menuId : menuIds) {
                if (menuMapper.selectById(menuId) == null) {
                    continue;
                }
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(menuId);
                rp.setPermissionType("MENU");
                rolePermissionMapper.insert(rp);
            }
        }
    }

    /** 查询角色已绑定的菜单ID */
    public List<String> getRolePermissionIds(String roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId)
                        .eq(SysRolePermission::getPermissionType, "MENU"))
                .stream().map(SysRolePermission::getPermissionId).toList();
    }

    private List<SysMenu> allMenus() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getStatus, 1)
                .orderByAsc(SysMenu::getSortOrder));
    }

    private MenuDto toDto(SysMenu menu) {
        MenuDto dto = new MenuDto();
        dto.setId(menu.getId());
        dto.setParentId(menu.getParentId());
        dto.setName(menu.getName());
        dto.setPath(menu.getPath());
        dto.setComponent(menu.getComponent());
        dto.setPermission(menu.getPermission());
        dto.setIcon(menu.getIcon());
        dto.setType(menu.getType());
        dto.setSortOrder(menu.getSortOrder());
        return dto;
    }
}
