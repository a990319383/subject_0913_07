package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.PlanCreateRequest;
import com.evops.dto.PlanUpdateRequest;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacChannelMapper;
import com.evops.mapper.TvacCurvePointMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.mapper.TvacReportMapper;
import com.evops.mapper.TvacTmFrameMapper;
import com.evops.vo.PlanDetailVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TvacPlanService {

    private final TvacPlanMapper planMapper;
    private final TvacArticleMapper articleMapper;
    private final TvacChannelMapper channelMapper;
    private final TvacCurvePointMapper curvePointMapper;
    private final TvacTmFrameMapper frameMapper;
    private final TvacReportMapper reportMapper;

    public TvacPlanService(TvacPlanMapper planMapper,
                           TvacArticleMapper articleMapper,
                           TvacChannelMapper channelMapper,
                           TvacCurvePointMapper curvePointMapper,
                           TvacTmFrameMapper frameMapper,
                           TvacReportMapper reportMapper) {
        this.planMapper = planMapper;
        this.articleMapper = articleMapper;
        this.channelMapper = channelMapper;
        this.curvePointMapper = curvePointMapper;
        this.frameMapper = frameMapper;
        this.reportMapper = reportMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public TvacPlan create(PlanCreateRequest req) {
        TvacArticle article = articleMapper.selectById(req.getArticleId());
        if (article == null) {
            throw BusinessException.of("试验件不存在: " + req.getArticleId());
        }
        validateTempRange(req.getHighTempC(), req.getLowTempC());
        TvacPlan plan = new TvacPlan();
        plan.setPlanCode(req.getPlanCode());
        plan.setPlanName(req.getPlanName());
        plan.setArticleId(req.getArticleId());
        plan.setHighTempC(req.getHighTempC());
        plan.setLowTempC(req.getLowTempC());
        plan.setVacuumPa(req.getVacuumPa());
        plan.setTargetCycles(req.getTargetCycles() == null ? 0 : req.getTargetCycles());
        plan.setPlanStartTime(req.getPlanStartTime());
        plan.setPlanEndTime(req.getPlanEndTime());
        plan.setStatus(TvacConst.PlanStatus.DRAFT);
        plan.setRemark(req.getRemark());
        planMapper.insert(plan);
        return plan;
    }

    private void validateTempRange(BigDecimal high, BigDecimal low) {
        if (high != null && low != null && high.compareTo(low) <= 0) {
            throw BusinessException.of("高温限必须高于低温限");
        }
    }

    /** 编辑：仅 DRAFT 状态允许 */
    public TvacPlan update(Long id, PlanUpdateRequest req) {
        TvacPlan plan = getById(id);
        if (!TvacConst.PlanStatus.DRAFT.equals(plan.getStatus())) {
            throw BusinessException.of("计划已" + statusText(plan.getStatus()) + "，不能再修改");
        }
        if (req.getPlanName() != null) {
            plan.setPlanName(req.getPlanName());
        }
        if (req.getHighTempC() != null) {
            plan.setHighTempC(req.getHighTempC());
        }
        if (req.getLowTempC() != null) {
            plan.setLowTempC(req.getLowTempC());
        }
        validateTempRange(plan.getHighTempC(), plan.getLowTempC());
        if (req.getVacuumPa() != null) {
            plan.setVacuumPa(req.getVacuumPa());
        }
        if (req.getTargetCycles() != null) {
            plan.setTargetCycles(req.getTargetCycles());
        }
        if (req.getPlanStartTime() != null) {
            plan.setPlanStartTime(req.getPlanStartTime());
        }
        if (req.getPlanEndTime() != null) {
            plan.setPlanEndTime(req.getPlanEndTime());
        }
        if (req.getRemark() != null) {
            plan.setRemark(req.getRemark());
        }
        planMapper.updateById(plan);
        return plan;
    }

    public TvacPlan getById(Long id) {
        TvacPlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw BusinessException.of("试验计划不存在: " + id);
        }
        return plan;
    }

    public List<TvacPlan> list(Long articleId, String status) {
        QueryWrapper<TvacPlan> qw = new QueryWrapper<>();
        if (articleId != null) {
            qw.eq("article_id", articleId);
        }
        if (status != null && !status.isEmpty()) {
            qw.eq("status", status);
        }
        qw.orderByDesc("id");
        return planMapper.selectList(qw);
    }

    /** 计划详情（关联试验件、遥测通道、判读报告） */
    public PlanDetailVo detail(Long id) {
        TvacPlan plan = getById(id);
        PlanDetailVo vo = new PlanDetailVo();
        vo.setPlan(plan);
        vo.setArticle(articleMapper.selectById(plan.getArticleId()));
        vo.setChannels(channelMapper.selectList(
                new QueryWrapper<TvacChannel>()
                        .eq("article_id", plan.getArticleId())
                        .eq("status", TvacConst.ChannelStatus.ENABLED)
                        .orderByAsc("id")));
        vo.setReport(reportMapper.selectOne(
                new QueryWrapper<TvacReport>().eq("plan_id", id)));
        return vo;
    }

    /**
     * 计划状态流转：DRAFT -> ISSUED -> RUNNING -> COMPLETED/TERMINATED；
     * 进入 RUNNING 时联动试验件 IN_TEST，进入 COMPLETED 时联动试验件 COMPLETED。
     */
    @Transactional(rollbackFor = Exception.class)
    public TvacPlan changeStatus(Long id, String target) {
        TvacPlan plan = getById(id);
        String current = plan.getStatus();
        if (current.equals(target)) {
            throw BusinessException.of("计划已处于目标状态: " + target);
        }
        if (!allowedTransition(current, target)) {
            throw BusinessException.of(
                    "计划状态不允许从 " + current + " 流转到 " + target);
        }
        plan.setStatus(target);
        planMapper.updateById(plan);

        TvacArticle article = articleMapper.selectById(plan.getArticleId());
        if (TvacConst.PlanStatus.RUNNING.equals(target)
                && TvacConst.ArticleStatus.REGISTERED.equals(article.getStatus())) {
            article.setStatus(TvacConst.ArticleStatus.IN_TEST);
            articleMapper.updateById(article);
        }
        if (TvacConst.PlanStatus.COMPLETED.equals(target)
                && TvacConst.ArticleStatus.IN_TEST.equals(article.getStatus())) {
            article.setStatus(TvacConst.ArticleStatus.COMPLETED);
            articleMapper.updateById(article);
        }
        return plan;
    }

    private boolean allowedTransition(String from, String to) {
        Set<String> allowed;
        switch (from) {
            case TvacConst.PlanStatus.DRAFT:
                allowed = new HashSet<>(Arrays.asList(
                        TvacConst.PlanStatus.ISSUED, TvacConst.PlanStatus.TERMINATED));
                break;
            case TvacConst.PlanStatus.ISSUED:
                allowed = new HashSet<>(Arrays.asList(
                        TvacConst.PlanStatus.RUNNING, TvacConst.PlanStatus.TERMINATED));
                break;
            case TvacConst.PlanStatus.RUNNING:
                allowed = new HashSet<>(Arrays.asList(
                        TvacConst.PlanStatus.COMPLETED, TvacConst.PlanStatus.TERMINATED));
                break;
            case TvacConst.PlanStatus.COMPLETED:
            case TvacConst.PlanStatus.TERMINATED:
            default:
                allowed = new HashSet<>();
        }
        return allowed.contains(to);
    }

    /**
     * 删除保护：已录入曲线点/遥测帧或已生成判读报告的计划不允许直接删除。
     * DRAFT 计划（无任何试验数据）方可删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TvacPlan plan = getById(id);
        if (!TvacConst.PlanStatus.DRAFT.equals(plan.getStatus())) {
            throw BusinessException.of("计划已" + statusText(plan.getStatus())
                    + "，不能直接删除");
        }
        Long curveCount = curvePointMapper.selectCount(
                new QueryWrapper<com.evops.entity.TvacCurvePoint>().eq("plan_id", id));
        Long frameCount = frameMapper.selectCount(
                new QueryWrapper<com.evops.entity.TvacTmFrame>().eq("plan_id", id));
        Long reportCount = reportMapper.selectCount(
                new QueryWrapper<TvacReport>().eq("plan_id", id));
        if ((curveCount != null && curveCount > 0)
                || (frameCount != null && frameCount > 0)
                || (reportCount != null && reportCount > 0)) {
            throw BusinessException.of("计划已存在试验数据或判读报告，不能直接删除");
        }
        planMapper.deleteById(id);
    }

    private String statusText(String status) {
        switch (status) {
            case TvacConst.PlanStatus.ISSUED: return "下达";
            case TvacConst.PlanStatus.RUNNING: return "开始";
            case TvacConst.PlanStatus.COMPLETED: return "完成";
            case TvacConst.PlanStatus.TERMINATED: return "终止";
            default: return status;
        }
    }
}
