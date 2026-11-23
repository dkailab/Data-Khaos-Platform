package com.datakhaos.workflow.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 区间补数受理结果：按日期升序返回每日的受理结论。
 */
@Data
public class BackfillResultDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;

    private String fromDate;

    private String toDate;

    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 业务日期 YYYY-MM-DD */
        private String bizDate;

        /** ACCEPTED / DUPLICATE / REJECTED */
        private String state;

        /** 受理成功时返回运行ID */
        private String runId;

        /** 受理失败时的拒绝码 */
        private String rejectCode;

        /** 受理失败时的原因 */
        private String rejectMessage;
    }
}
