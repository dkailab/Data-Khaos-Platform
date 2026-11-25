package com.datakhaos.permission.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户权限视图：角色、权限标识、可见菜单
 */
@Data
public class UserPermissionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;

    /** Whether the platform account exists and is enabled. */
    private Boolean active;

    /** 角色编码集合 */
    private List<String> roles = new ArrayList<>();

    /** 权限标识集合（含菜单 permission 与 API 权限） */
    private List<String> permissions = new ArrayList<>();

    /** 可见菜单（扁平列表，前端构建树） */
    private List<MenuDto> menus = new ArrayList<>();

    /** 用户加入的项目组集合 */
    private List<ProjectGroupDto> projectGroups = new ArrayList<>();

    /** 当前项目组ID（上下文，主项目组或用户切换后的选中组） */
    private String projectGroupId;

    /** 当前项目组角色合并后的能力位集合（操作权限，与菜单 permission 一起决定能做什么） */
    private List<String> capabilityFlags = new ArrayList<>();
}
