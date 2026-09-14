package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.FrameCreateRequest;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import com.evops.entity.TvacTmFrame;
import com.evops.mapper.TvacChannelMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.mapper.TvacReportMapper;
import com.evops.mapper.TvacTmFrameMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TvacTmFrameService {

    private final TvacTmFrameMapper frameMapper;
    private final TvacPlanMapper planMapper;
    private final TvacChannelMapper channelMapper;
    private final TvacReportMapper reportMapper;

    public TvacTmFrameService(TvacTmFrameMapper frameMapper,
                              TvacPlanMapper planMapper,
                              TvacChannelMapper channelMapper,
                              TvacReportMapper reportMapper) {
        this.frameMapper = frameMapper;
        this.planMapper = planMapper;
        this.channelMapper = channelMapper;
        this.reportMapper = reportMapper;
    }

    /** 录入一帧遥测：校验计划/通道归属与状态，按通道限界自动打越限标记 */
    public TvacTmFrame create(FrameCreateRequest req) {
        TvacPlan plan = planMapper.selectById(req.getPlanId());
        if (plan == null) {
            throw BusinessException.of("试验计划不存在: " + req.getPlanId());
        }
        if (!TvacConst.PlanStatus.RUNNING.equals(plan.getStatus())) {
            throw BusinessException.of("只有进行中的计划才能录入遥测帧，当前状态: "
                    + plan.getStatus());
        }
        TvacChannel channel = channelMapper.selectById(req.getChannelId());
        if (channel == null) {
            throw BusinessException.of("遥测通道不存在: " + req.getChannelId());
        }
        if (!channel.getArticleId().equals(plan.getArticleId())) {
            throw BusinessException.of("通道不属于本计划的试验件，拒绝收帧");
        }
        if (TvacConst.ChannelStatus.DISABLED.equals(channel.getStatus())) {
            throw BusinessException.of("通道已停用，不能再录入遥测帧: "
                    + channel.getChannelCode());
        }
        ensureNotPosted(plan.getId());

        TvacTmFrame frame = new TvacTmFrame();
        frame.setFrameSeq(req.getFrameSeq());
        frame.setPlanId(plan.getId());
        frame.setChannelId(channel.getId());
        frame.setArticleId(plan.getArticleId());
        frame.setCycleNo(req.getCycleNo());
        frame.setFrameTime(req.getFrameTime() == null ? LocalDateTime.now() : req.getFrameTime());
        frame.setRawValue(req.getRawValue());
        frame.setEngValue(req.getEngValue());
        frame.setLimitFlag(judgeLimit(req.getEngValue(), channel));
        frameMapper.insert(frame);
        return frame;
    }

    /** 批量收帧，整批校验、整批回滚 */
    @Transactional(rollbackFor = Exception.class)
    public List<TvacTmFrame> createBatch(List<FrameCreateRequest> list) {
        java.util.List<TvacTmFrame> saved = new java.util.ArrayList<>();
        for (FrameCreateRequest req : list) {
            saved.add(create(req));
        }
        return saved;
    }

    private String judgeLimit(BigDecimal value, TvacChannel channel) {
        if (value == null) {
            return TvacConst.LimitFlag.NORMAL;
        }
        if (channel.getUpperLimit() != null && value.compareTo(channel.getUpperLimit()) > 0) {
            return TvacConst.LimitFlag.HIGH;
        }
        if (channel.getLowerLimit() != null && value.compareTo(channel.getLowerLimit()) < 0) {
            return TvacConst.LimitFlag.LOW;
        }
        return TvacConst.LimitFlag.NORMAL;
    }

    private void ensureNotPosted(Long planId) {
        TvacReport report = reportMapper.selectOne(
                new QueryWrapper<TvacReport>().eq("plan_id", planId));
        if (report != null && report.getPostedTime() != null) {
            throw BusinessException.of("判读报告已落账，不能再补录遥测帧");
        }
    }

    public List<TvacTmFrame> listByPlan(Long planId, Integer cycleNo, String limitFlag) {
        QueryWrapper<TvacTmFrame> qw = new QueryWrapper<>();
        qw.eq("plan_id", planId);
        if (cycleNo != null) {
            qw.eq("cycle_no", cycleNo);
        }
        if (limitFlag != null && !limitFlag.isEmpty()) {
            qw.eq("limit_flag", limitFlag);
        }
        qw.orderByAsc("frame_time").orderByAsc("id");
        return frameMapper.selectList(qw);
    }
}
