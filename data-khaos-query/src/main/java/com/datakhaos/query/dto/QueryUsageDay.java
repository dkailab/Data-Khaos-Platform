package com.datakhaos.query.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

public record QueryUsageDay(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate submittedOn,
                            long succeeded, long failed, long cancelled, long active,
                            long timedSuccesses, long executionSeconds) {
    public static QueryUsageDay empty(LocalDate day) {
        return new QueryUsageDay(day, 0, 0, 0, 0, 0, 0);
    }
}
