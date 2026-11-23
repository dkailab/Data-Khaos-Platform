package com.datakhaos.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.permission.entity.SgProjectGroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SgProjectGroupMemberMapper extends BaseMapper<SgProjectGroupMember> {
    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 1")
    int countActiveUser(@Param("userId") String userId);
}