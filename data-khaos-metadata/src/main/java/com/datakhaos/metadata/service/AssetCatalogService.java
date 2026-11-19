package com.datakhaos.metadata.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.metadata.dto.AssetTable;
import com.datakhaos.metadata.entity.MetaColumn;
import com.datakhaos.metadata.entity.MetaTableLineage;
import com.datakhaos.metadata.mapper.AssetCatalogMapper;
import com.datakhaos.permission.api.service.PermissionApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AssetCatalogService {
    private final AssetCatalogMapper mapper;
    private final MetadataService metadata;
    private final PermissionApiClient permission;

    public PageResult<AssetTable> search(String query, long current, long size) {
        if (current < 1 || size < 1 || size > 100 || (query != null && query.length() > 100)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED);
        }
        Scope scope = visibleScope();
        String keyword = query == null || query.isBlank() ? null : "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
        var page = mapper.catalog(new Page<>(current, size), keyword, scope.admin(), scope.grants());
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public Detail detail(String id) {
        Scope scope = visibleScope();
        AssetTable table = mapper.visibleTables(List.of(id), scope.admin(), scope.grants()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "资产不存在或不可见"));
        List<MetaTableLineage> edges = metadata.lineage(id);
        List<String> ids = edges.stream()
                .flatMap(edge -> Stream.of(edge.getSourceTableId(), edge.getTargetTableId()))
                .filter(neighbor -> neighbor != null && !neighbor.equals(id)).distinct().toList();
        Map<String, AssetTable> neighbors = ids.isEmpty() ? Map.of()
                : mapper.visibleTables(ids, scope.admin(), scope.grants()).stream()
                        .collect(Collectors.toMap(AssetTable::getId, neighbor -> neighbor));
        List<Relation> relations = edges.stream().map(edge -> {
            boolean upstream = id.equals(edge.getTargetTableId());
            AssetTable neighbor = neighbors.get(upstream ? edge.getSourceTableId() : edge.getTargetTableId());
            return neighbor == null ? null : new Relation(neighbor, upstream, edge.getRelationType());
        }).filter(Objects::nonNull).distinct().toList();
        return new Detail(table, relations);
    }

    public PageResult<MetaColumn> fields(String id, long current, long size) {
        if (current < 1 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED);
        }
        Scope scope = visibleScope();
        if (mapper.visibleTables(List.of(id), scope.admin(), scope.grants()).isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资产不存在或不可见");
        }
        return metadata.columnPage(current, size, id);
    }

    private Scope visibleScope() {
        String userId = MetadataHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (MetadataHolder.isSuperAdmin()) {
            return new Scope(true, List.of());
        }
        List<Map<String, Object>> grants = permission.getUserTablePermissions(userId).stream()
                .filter(grant -> "SELECT".equalsIgnoreCase((String) grant.get("permissionType"))
                        || "ALL".equalsIgnoreCase((String) grant.get("permissionType")))
                .distinct().toList();
        return new Scope(false, grants);
    }

    private record Scope(boolean admin, List<Map<String, Object>> grants) {}
    public record Detail(AssetTable table, List<Relation> relations) {}
    public record Relation(AssetTable table, boolean upstream, String type) {}
}
