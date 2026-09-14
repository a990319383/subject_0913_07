package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.CurvePointCreateRequest;
import com.evops.entity.TvacCurvePoint;
import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import com.evops.mapper.TvacCurvePointMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.mapper.TvacReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TvacCurvePointService {

    private final TvacCurvePointMapper curvePointMapper;
    private final TvacPlanMapper planMapper;
    private final TvacReportMapper reportMapper;

    public TvacCurvePointService(TvacCurvePointMapper curvePointMapper,
                                 TvacPlanMapper planMapper,
                                 TvacReportMapper reportMapper) {
        this.curvePointMapper = curvePointMapper;
        this.planMapper = planMapper;
        this.reportMapper = reportMapper;
    }

    /** 录入一个温压曲线点，仅 RUNNING 计划可录入，且该计划尚未判读落账 */
    public TvacCurvePoint create(CurvePointCreateRequest req) {
        TvacPlan plan = planMapper.selectById(req.getPlanId());
        if (plan == null) {
            throw BusinessException.of("试验计划不存在: " + req.getPlanId());
        }
        if (!TvacConst.PlanStatus.RUNNING.equals(plan.getStatus())) {
            throw BusinessException.of("只有进行中的计划才能录入曲线点，当前状态: "
                    + plan.getStatus());
        }
        ensureNotPosted(plan.getId());

        TvacCurvePoint point = new TvacCurvePoint();
        point.setPlanId(plan.getId());
        point.setArticleId(plan.getArticleId());
        point.setCycleNo(req.getCycleNo());
        point.setPointTime(req.getPointTime() == null ? LocalDateTime.now() : req.getPointTime());
        point.setOffsetSec(req.getOffsetSec() == null ? 0 : req.getOffsetSec());
        point.setTemperatureC(req.getTemperatureC());
        point.setPressurePa(req.getPressurePa());
        curvePointMapper.insert(point);
        return point;
    }

    /** 批量录入，整批校验、整批回滚 */
    @Transactional(rollbackFor = Exception.class)
    public List<TvacCurvePoint> createBatch(List<CurvePointCreateRequest> list) {
        java.util.List<TvacCurvePoint> saved = new java.util.ArrayList<>();
        for (CurvePointCreateRequest req : list) {
            saved.add(create(req));
        }
        return saved;
    }

    private void ensureNotPosted(Long planId) {
        TvacReport report = reportMapper.selectOne(
                new QueryWrapper<TvacReport>().eq("plan_id", planId));
        if (report != null && report.getPostedTime() != null) {
            throw BusinessException.of("判读报告已落账，不能再补录曲线数据");
        }
    }

    public List<TvacCurvePoint> listByPlan(Long planId, Integer cycleNo) {
        QueryWrapper<TvacCurvePoint> qw = new QueryWrapper<>();
        qw.eq("plan_id", planId);
        if (cycleNo != null) {
            qw.eq("cycle_no", cycleNo);
        }
        qw.orderByAsc("cycle_no").orderByAsc("offset_sec").orderByAsc("id");
        return curvePointMapper.selectList(qw);
    }
}
