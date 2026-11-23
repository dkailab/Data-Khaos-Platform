package com.datakhaos.workflow.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 区间补数受理结果：按日期升序返回每日的受理结论。
 */
@Data
public class BackfillResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String fromDate;
    private String toDate;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        private String bizDate;
        private String state;
        private String runId;
        private String rejectCode;
        private String rejectMessage;
        /** 冲突时的活跃运行ID（RUN_ALREADY_ACTIVE） */
        private String activeRunId;
    }
}
