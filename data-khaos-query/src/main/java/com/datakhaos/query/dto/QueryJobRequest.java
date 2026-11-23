package com.datakhaos.query.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryJobRequest extends QueryExecuteRequest {
    private String requestId;
}
