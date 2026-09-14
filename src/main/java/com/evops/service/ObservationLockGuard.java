package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.entity.TvacReport;
import com.evops.mapper.TvacReportMapper;
import org.springframework.stereotype.Component;

/**
 * 观测数据锁定判定：判读报告已验收或已落账后，曲线点/遥测帧/结论一律不得覆盖。
 * 判读未验收的报告允许补录与重新判读（与 TvacReportService 既有口径一致）。
 */
@Component
public class ObservationLockGuard {

    private final TvacReportMapper reportMapper;

    public ObservationLockGuard(TvacReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    /** 返回锁定原因；未锁定返回 null */
    public String lockedReason(Long planId) {
        TvacReport report = reportMapper.selectOne(
                new QueryWrapper<TvacReport>().eq("plan_id", planId));
        if (report == null) {
            return null;
        }
        if (report.getPostedTime() != null) {
            return "判读报告已落账，观测数据锁定，不得覆盖（报告: " + report.getReportNo() + "）";
        }
        if (report.getAcceptedTime() != null) {
            return "判读报告已验收，观测数据锁定，不得覆盖（报告: " + report.getReportNo() + "）";
        }
        return null;
    }
}
