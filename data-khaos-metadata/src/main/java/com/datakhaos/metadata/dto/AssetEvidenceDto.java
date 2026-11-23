package com.datakhaos.metadata.dto;

import com.datakhaos.dquality.api.model.DqSnapshotDto;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.workflow.api.model.WorkflowRunDto;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * PR3 资产证据：从数据地图的资产（表）回查某业务日分区的发布与质量证据。
 *
 * <p>「已发布数据的来源」始终指向实际生产该版本的成功运行；失败运行只在
 * {@link #recentInstances} 中可见，避免失败运行覆盖已发布来源。</p>
 */
@Data
public class AssetEvidenceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前资产（数据地图中的表） */
    private AssetTable asset;

    /** 查询的业务日期（可空=不限日期） */
    private String bizDate;

    /** 最近一次成功发布的管道实例（可空=该分区无成功发布记录） */
    private PipelineInstanceDto publishedInstance;

    /** 生产该版本的成功工作流运行（可空） */
    private WorkflowRunDto publishedRun;

    /** 本次发布所对应的 PASS 质量快照（可空） */
    private DqSnapshotDto qualityPass;

    /** 兼容字段，与本次发布的质量快照一致 */
    private DqSnapshotDto qualityLatest;

    /** 质量快照实际来源表：本次发布验证的表或直接上游表（可空=无任何质量证据） */
    private String qualitySourceTable;

    /** 近期管道实例（含失败，按时间倒序） */
    private List<PipelineInstanceDto> recentInstances;
}
