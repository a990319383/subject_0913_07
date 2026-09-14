package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.ReportAcceptRequest;
import com.evops.dto.ReportCreateRequest;
import com.evops.dto.ReportJudgeRequest;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacCurvePointMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.mapper.TvacReportMapper;
import com.evops.mapper.TvacTmFrameMapper;
import com.evops.vo.PlanCurveStatsVo;
import com.evops.vo.PlanFrameStatsVo;
import com.evops.vo.ReportDetailVo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TvacReportService {

    private final TvacReportMapper reportMapper;
    private final TvacPlanMapper planMapper;
    private final TvacArticleMapper articleMapper;
    private final TvacCurvePointMapper curvePointMapper;
    private final TvacTmFrameMapper frameMapper;

    public TvacReportService(TvacReportMapper reportMapper,
                             TvacPlanMapper planMapper,
                             TvacArticleMapper articleMapper,
                             TvacCurvePointMapper curvePointMapper,
                             TvacTmFrameMapper frameMapper) {
        this.reportMapper = reportMapper;
        this.planMapper = planMapper;
        this.articleMapper = articleMapper;
        this.curvePointMapper = curvePointMapper;
        this.frameMapper = frameMapper;
    }

    /** 生成判读报告（PENDING）：一份计划至多一份，且计划须已完成 */
    public TvacReport create(ReportCreateRequest req) {
        TvacPlan plan = planMapper.selectById(req.getPlanId());
        if (plan == null) {
            throw BusinessException.of("试验计划不存在: " + req.getPlanId());
        }
        if (!TvacConst.PlanStatus.COMPLETED.equals(plan.getStatus())) {
            throw BusinessException.of("只有已完成的计划才能生成判读报告，当前状态: "
                    + plan.getStatus());
        }
        Long exists = reportMapper.selectCount(
                new QueryWrapper<TvacReport>().eq("plan_id", plan.getId()));
        if (exists != null && exists > 0) {
            throw BusinessException.of("该计划已存在判读报告，一计划一报告");
        }
        TvacReport report = new TvacReport();
        report.setReportNo(req.getReportNo());
        report.setPlanId(plan.getId());
        report.setConclusion(TvacConst.Conclusion.PENDING);
        report.setActualCycles(0);
        report.setTotalFrames(0);
        report.setAbnormalFrames(0);
        report.setRemark(req.getRemark());
        reportMapper.insert(report);
        return report;
    }

    public TvacReport getById(Long id) {
        TvacReport report = reportMapper.selectById(id);
        if (report == null) {
            throw BusinessException.of("判读报告不存在: " + id);
        }
        return report;
    }

    public List<TvacReport> list(Long planId, String conclusion) {
        QueryWrapper<TvacReport> qw = new QueryWrapper<>();
        if (planId != null) {
            qw.eq("plan_id", planId);
        }
        if (conclusion != null && !conclusion.isEmpty()) {
            qw.eq("conclusion", conclusion);
        }
        qw.orderByDesc("id");
        return reportMapper.selectList(qw);
    }

    public ReportDetailVo detail(Long id) {
        TvacReport report = getById(id);
        TvacPlan plan = planMapper.selectById(report.getPlanId());
        TvacArticle article = plan == null ? null : articleMapper.selectById(plan.getArticleId());
        ReportDetailVo vo = new ReportDetailVo();
        vo.setReport(report);
        vo.setPlan(plan);
        if (article != null) {
            vo.setArticleId(article.getId());
            vo.setArticleCode(article.getArticleCode());
            vo.setArticleName(article.getArticleName());
        }
        return vo;
    }

    /**
     * 提交判读结论：自动汇总实际循环次数、总帧数、异常帧数、温度极值与最低真空度。
     * 已验收/已落账的报告不允许重新判读。
     */
    public TvacReport judge(Long id, ReportJudgeRequest req) {
        TvacReport report = getById(id);
        if (report.getAcceptedTime() != null || report.getPostedTime() != null) {
            throw BusinessException.of("报告已验收或已落账，判读结论锁定，不能修改");
        }
        validateConclusion(req.getConclusion());
        if (TvacConst.Conclusion.PENDING.equals(req.getConclusion())) {
            throw BusinessException.of("判读结论不能为 PENDING");
        }

        PlanCurveStatsVo curve = curvePointMapper.statsByPlan(report.getPlanId());
        PlanFrameStatsVo frames = frameMapper.statsByPlan(report.getPlanId());

        int actualCycles = Math.max(
                curve.getMaxCycle() == null ? 0 : curve.getMaxCycle(),
                frames.getMaxCycle() == null ? 0 : frames.getMaxCycle());
        report.setActualCycles(actualCycles);
        report.setTotalFrames(frames.getTotalFrames() == null ? 0 : frames.getTotalFrames().intValue());
        report.setAbnormalFrames(frames.getAbnormalFrames() == null ? 0 : frames.getAbnormalFrames().intValue());
        report.setHighTempReached(curve.getHighTempReached());
        report.setLowTempReached(curve.getLowTempReached());
        report.setMinPressurePa(curve.getMinPressurePa());
        report.setConclusion(req.getConclusion());
        report.setJudgedBy(req.getJudgedBy());
        report.setJudgedTime(LocalDateTime.now());
        if (req.getRemark() != null) {
            report.setRemark(req.getRemark());
        }
        reportMapper.updateById(report);
        return report;
    }

    /** 验收：仅已判读且结论为合格/有条件合格的报告可验收 */
    public TvacReport accept(Long id, ReportAcceptRequest req) {
        TvacReport report = getById(id);
        if (report.getPostedTime() != null) {
            throw BusinessException.of("报告已落账，不能重复验收");
        }
        if (report.getJudgedTime() == null) {
            throw BusinessException.of("报告尚未判读，不能验收");
        }
        if (TvacConst.Conclusion.UNQUALIFIED.equals(report.getConclusion())) {
            throw BusinessException.of("结论为不合格的报告不能验收");
        }
        report.setAcceptedBy(req.getAcceptedBy());
        report.setAcceptedTime(LocalDateTime.now());
        if (req.getRemark() != null) {
            report.setRemark(req.getRemark());
        }
        reportMapper.updateById(report);
        return report;
    }

    /** 落账：验收后的报告方可落账，落账后数据锁定，任何试验数据不得再补录 */
    public TvacReport post(Long id) {
        TvacReport report = getById(id);
        if (report.getPostedTime() != null) {
            throw BusinessException.of("报告已落账，请勿重复操作");
        }
        if (report.getAcceptedTime() == null) {
            throw BusinessException.of("报告尚未验收，不能落账");
        }
        report.setPostedTime(LocalDateTime.now());
        reportMapper.updateById(report);
        return report;
    }

    /**
     * 删除保护：已验收或已落账的判读报告不能直接删除。
     * 未判读（PENDING）或判读未验收的报告允许删除重做。
     */
    public void delete(Long id) {
        TvacReport report = getById(id);
        if (report.getAcceptedTime() != null) {
            throw BusinessException.of("判读报告已验收，不能直接删除");
        }
        if (report.getPostedTime() != null) {
            throw BusinessException.of("判读报告已落账，不能直接删除");
        }
        reportMapper.deleteById(id);
    }

    private void validateConclusion(String conclusion) {
        Set<String> valid = new HashSet<>(Arrays.asList(
                TvacConst.Conclusion.PENDING,
                TvacConst.Conclusion.QUALIFIED,
                TvacConst.Conclusion.UNQUALIFIED,
                TvacConst.Conclusion.CONDITIONAL));
        if (!valid.contains(conclusion)) {
            throw BusinessException.of("不支持的判读结论: " + conclusion);
        }
    }
}
