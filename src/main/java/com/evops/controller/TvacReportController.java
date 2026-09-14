package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ReportAcceptRequest;
import com.evops.dto.ReportCreateRequest;
import com.evops.dto.ReportJudgeRequest;
import com.evops.entity.TvacReport;
import com.evops.service.TvacReportService;
import com.evops.vo.ReportDetailVo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/tvac/reports")
public class TvacReportController {

    private final TvacReportService reportService;

    public TvacReportController(TvacReportService reportService) {
        this.reportService = reportService;
    }

    /** 生成判读报告（计划完成后，一计划一报告） */
    @PostMapping
    public ApiResponse<TvacReport> create(@Valid @RequestBody ReportCreateRequest req) {
        return ApiResponse.ok(reportService.create(req));
    }

    @GetMapping
    public ApiResponse<List<TvacReport>> list(
            @RequestParam(required = false) Long planId,
            @RequestParam(required = false) String conclusion) {
        return ApiResponse.ok(reportService.list(planId, conclusion));
    }

    @GetMapping("/{id}")
    public ApiResponse<TvacReport> get(@PathVariable Long id) {
        return ApiResponse.ok(reportService.getById(id));
    }

    /** 关联查询：报告 + 计划 + 试验件 */
    @GetMapping("/{id}/detail")
    public ApiResponse<ReportDetailVo> detail(@PathVariable Long id) {
        return ApiResponse.ok(reportService.detail(id));
    }

    /** 判读：提交结论并自动汇总循环次数/帧数/越限数/温压极值 */
    @PutMapping("/{id}/judge")
    public ApiResponse<TvacReport> judge(@PathVariable Long id,
                                         @Valid @RequestBody ReportJudgeRequest req) {
        return ApiResponse.ok(reportService.judge(id, req));
    }

    /** 验收 */
    @PutMapping("/{id}/accept")
    public ApiResponse<TvacReport> accept(@PathVariable Long id,
                                          @Valid @RequestBody ReportAcceptRequest req) {
        return ApiResponse.ok(reportService.accept(id, req));
    }

    /** 落账 */
    @PutMapping("/{id}/post")
    public ApiResponse<TvacReport> post(@PathVariable Long id) {
        return ApiResponse.ok(reportService.post(id));
    }

    /** 删除：已验收或已落账的报告受保护 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        reportService.delete(id);
        return ApiResponse.ok(null);
    }
}
