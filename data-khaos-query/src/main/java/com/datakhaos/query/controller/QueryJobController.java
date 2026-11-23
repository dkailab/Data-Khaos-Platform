package com.datakhaos.query.controller;

import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.QueryJobRequest;
import com.datakhaos.query.dto.QueryJobView;
import com.datakhaos.query.dto.QueryUsageDay;
import com.datakhaos.query.entity.QueryJobStatus;
import com.datakhaos.query.service.QueryJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/query/jobs")
@RequiredArgsConstructor
public class QueryJobController {
    private final QueryJobService jobs;

    @PostMapping
    public R<QueryJobView> submit(@RequestBody QueryJobRequest request) {
        return R.ok(jobs.submit(request));
    }

    @GetMapping
    public R<PageResult<QueryJobView>> page(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) QueryJobStatus status,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedOn) {
        return R.ok(jobs.page(current, size, status, submittedOn));
    }

    @GetMapping("/usage")
    public R<List<QueryUsageDay>> usage(@RequestParam(defaultValue = "7") int days) {
        return R.ok(jobs.usage(days));
    }

    @GetMapping("/{id}")
    public R<QueryJobView> detail(@PathVariable String id) {
        return R.ok(jobs.detail(id));
    }

    @GetMapping("/{id}/result")
    public R<QueryResult> result(@PathVariable String id) {
        return R.ok(jobs.result(id));
    }

    @PostMapping("/{id}/cancel")
    public R<QueryJobView> cancel(@PathVariable String id) {
        return R.ok(jobs.cancel(id));
    }
}
