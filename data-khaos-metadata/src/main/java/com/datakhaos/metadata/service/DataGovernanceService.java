package com.datakhaos.metadata.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.metadata.entity.MetaDictItem;
import com.datakhaos.metadata.entity.MetaDictType;
import com.datakhaos.metadata.entity.MetaStandard;
import com.datakhaos.metadata.mapper.MetaDictItemMapper;
import com.datakhaos.metadata.mapper.MetaDictTypeMapper;
import com.datakhaos.metadata.mapper.MetaStandardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 数据治理服务：数据字典（类型 + 项）与数据标准配置的维护与查询。
 * 全局共享配置，供仓表字段语义化、枚举回显与建表标准参考使用。
 */
@Service
@RequiredArgsConstructor
public class DataGovernanceService {

    private final MetaDictTypeMapper dictTypeMapper;
    private final MetaDictItemMapper dictItemMapper;
    private final MetaStandardMapper standardMapper;

    // ---------------- 字典类型 ----------------

    public PageResult<MetaDictType> typePage(long current, long size, String keyword, Integer status) {
        Page<MetaDictType> page = dictTypeMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MetaDictType>()
                        .like(StrUtil.isNotBlank(keyword), MetaDictType::getTypeName, keyword)
                        .or(StrUtil.isNotBlank(keyword), w -> w.like(MetaDictType::getTypeCode, keyword))
                        .eq(status != null, MetaDictType::getStatus, status)
                        .orderByAsc(MetaDictType::getSortOrder));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public List<MetaDictType> typeList(String keyword) {
        return dictTypeMapper.selectList(new LambdaQueryWrapper<MetaDictType>()
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(MetaDictType::getTypeName, keyword)
                        .or().like(MetaDictType::getTypeCode, keyword))
                .orderByAsc(MetaDictType::getSortOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public void createType(MetaDictType type) {
        if (StrUtil.isBlank(type.getTypeCode())) {
            throw new BusinessException("字典类型编码不能为空");
        }
        if (StrUtil.isBlank(type.getTypeName())) {
            throw new BusinessException("字典类型名称不能为空");
        }
        if (type.getStatus() == null) {
            type.setStatus(1);
        }
        if (type.getSortOrder() == null) {
            type.setSortOrder(0);
        }
        Long cnt = dictTypeMapper.selectCount(new LambdaQueryWrapper<MetaDictType>()
                .eq(MetaDictType::getTypeCode, type.getTypeCode()));
        if (cnt != null && cnt > 0) {
            throw new BusinessException("字典类型编码已存在: " + type.getTypeCode());
        }
        type.setId(null);
        dictTypeMapper.insert(type);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateType(String id, MetaDictType type) {
        MetaDictType exist = dictTypeMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("字典类型不存在");
        }
        type.setId(id);
        dictTypeMapper.updateById(type);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteType(String id) {
        MetaDictType exist = dictTypeMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("字典类型不存在");
        }
        // 连带删除该类型下的所有字典项
        dictItemMapper.delete(new LambdaQueryWrapper<MetaDictItem>()
                .eq(MetaDictItem::getTypeId, id));
        dictTypeMapper.deleteById(id);
    }

    // ---------------- 字典项 ----------------

    public PageResult<MetaDictItem> itemPage(long current, long size, String typeId, String keyword, Integer status) {
        if (StrUtil.isBlank(typeId)) {
            throw new BusinessException("请先选择字典类型");
        }
        Page<MetaDictItem> page = dictItemMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MetaDictItem>()
                        .eq(MetaDictItem::getTypeId, typeId)
                        .eq(status != null, MetaDictItem::getStatus, status)
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(MetaDictItem::getItemName, keyword)
                                .or().like(MetaDictItem::getItemCode, keyword))
                        .orderByAsc(MetaDictItem::getSortOrder));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 按字典类型编码取全部启用项（供枚举下拉/语义回显） */
    public List<MetaDictItem> itemListByTypeCode(String typeCode) {
        if (StrUtil.isBlank(typeCode)) {
            return List.of();
        }
        MetaDictType type = dictTypeMapper.selectOne(new LambdaQueryWrapper<MetaDictType>()
                .eq(MetaDictType::getTypeCode, typeCode)
                .last("limit 1"));
        if (type == null) {
            return List.of();
        }
        return dictItemMapper.selectList(new LambdaQueryWrapper<MetaDictItem>()
                .eq(MetaDictItem::getTypeId, type.getId())
                .eq(MetaDictItem::getStatus, 1)
                .orderByAsc(MetaDictItem::getSortOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public void createItem(MetaDictItem item) {
        if (StrUtil.isBlank(item.getTypeId())) {
            throw new BusinessException("请先选择字典类型");
        }
        if (StrUtil.isBlank(item.getItemCode())) {
            throw new BusinessException("字典项编码不能为空");
        }
        valType(item.getTypeId());
        Long cnt = dictItemMapper.selectCount(new LambdaQueryWrapper<MetaDictItem>()
                .eq(MetaDictItem::getTypeId, item.getTypeId())
                .eq(MetaDictItem::getItemCode, item.getItemCode()));
        if (cnt != null && cnt > 0) {
            throw new BusinessException("该类型下字典项编码已存在: " + item.getItemCode());
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        if (item.getSortOrder() == null) {
            item.setSortOrder(0);
        }
        item.setId(null);
        dictItemMapper.insert(item);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateItem(String id, MetaDictItem item) {
        MetaDictItem exist = dictItemMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("字典项不存在");
        }
        item.setId(id);
        dictItemMapper.updateById(item);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(String id) {
        if (dictItemMapper.selectById(id) == null) {
            throw new BusinessException("字典项不存在");
        }
        dictItemMapper.deleteById(id);
    }

    private void valType(String typeId) {
        if (dictTypeMapper.selectById(typeId) == null) {
            throw new BusinessException("字典类型不存在");
        }
    }

    // ---------------- 数据标准 ----------------

    public PageResult<MetaStandard> standardPage(long current, long size, String keyword, String category, Integer status) {
        Page<MetaStandard> page = standardMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MetaStandard>()
                        .eq(StrUtil.isNotBlank(category), MetaStandard::getCategory, category)
                        .eq(status != null, MetaStandard::getStatus, status)
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(MetaStandard::getStdName, keyword)
                                .or().like(MetaStandard::getStdCode, keyword))
                        .orderByAsc(MetaStandard::getSortOrder));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public void createStandard(MetaStandard standard) {
        if (StrUtil.isBlank(standard.getStdCode())) {
            throw new BusinessException("标准编码不能为空");
        }
        if (StrUtil.isBlank(standard.getStdName())) {
            throw new BusinessException("标准名称不能为空");
        }
        Long cnt = standardMapper.selectCount(new LambdaQueryWrapper<MetaStandard>()
                .eq(MetaStandard::getStdCode, standard.getStdCode()));
        if (cnt != null && cnt > 0) {
            throw new BusinessException("标准编码已存在: " + standard.getStdCode());
        }
        if (standard.getStatus() == null) {
            standard.setStatus(1);
        }
        if (standard.getSortOrder() == null) {
            standard.setSortOrder(0);
        }
        standard.setId(null);
        standardMapper.insert(standard);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStandard(String id, MetaStandard standard) {
        if (standardMapper.selectById(id) == null) {
            throw new BusinessException("数据标准不存在");
        }
        standard.setId(id);
        standardMapper.updateById(standard);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteStandard(String id) {
        if (standardMapper.selectById(id) == null) {
            throw new BusinessException("数据标准不存在");
        }
        standardMapper.deleteById(id);
    }
}