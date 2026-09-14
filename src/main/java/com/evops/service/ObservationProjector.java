package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.constant.TvacConst;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacCurvePoint;
import com.evops.entity.TvacObservation;
import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import com.evops.entity.TvacTmFrame;
import com.evops.mapper.TvacChannelMapper;
import com.evops.mapper.TvacCurvePointMapper;
import com.evops.mapper.TvacObservationMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.mapper.TvacReportMapper;
import com.evops.mapper.TvacTmFrameMapper;
import com.evops.vo.PlanCurveStatsVo;
import com.evops.vo.PlanFrameStatsVo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 把一条校验通过的归一化观测记录投影到业务表（曲线点 / 遥测帧 / 判读报告），
 * 并维护归一化台账 {@link TvacObservation}。
 *
 * <p>幂等口径：
 * <ul>
 *   <li>曲线点业务键 (plan, cycle, offset)，命中即覆盖，返回 UPDATED；</li>
 *   <li>遥测帧业务键 frame_seq，命中且归属一致才覆盖，帧号跨通道冲突直接失败；</li>
 *   <li>判读结论按 plan 落报告，已验收/已落账拒绝覆盖。</li>
 * </ul>
 * 本组件不自行声明事务，随分片事务一起提交或回滚；任何业务拒绝都以
 * {@link RejectException} 抛出，由分片处理器逐行隔离。
 */
@Component
public class ObservationProjector {

    /** 业务规则拒绝（区别于系统异常）：消息即写入错误明细的失败原因 */
    public static class RejectException extends RuntimeException {
        public RejectException(String message) {
            super(message);
        }
    }

    /** 投影结果：SUCCESS 新增 / UPDATED 覆盖 */
    public static class Outcome {
        final String result;
        final String targetTable;
        final Long targetId;

        Outcome(String result, String targetTable, Long targetId) {
            this.result = result;
            this.targetTable = targetTable;
            this.targetId = targetId;
        }
    }

    private final TvacPlanMapper planMapper;
    private final TvacChannelMapper channelMapper;
    private final TvacCurvePointMapper curvePointMapper;
    private final TvacTmFrameMapper frameMapper;
    private final TvacReportMapper reportMapper;
    private final TvacObservationMapper observationMapper;
    private final ObservationLockGuard lockGuard;
    private final FrameLimitJudge limitJudge;

    public ObservationProjector(TvacPlanMapper planMapper,
                                TvacChannelMapper channelMapper,
                                TvacCurvePointMapper curvePointMapper,
                                TvacTmFrameMapper frameMapper,
                                TvacReportMapper reportMapper,
                                TvacObservationMapper observationMapper,
                                ObservationLockGuard lockGuard,
                                FrameLimitJudge limitJudge) {
        this.planMapper = planMapper;
        this.channelMapper = channelMapper;
        this.curvePointMapper = curvePointMapper;
        this.frameMapper = frameMapper;
        this.reportMapper = reportMapper;
        this.observationMapper = observationMapper;
        this.lockGuard = lockGuard;
        this.limitJudge = limitJudge;
    }

    public Outcome project(TvacObservation obs, Long batchId) {
        switch (obs.getRecordType()) {
            case TvacConst.RecordType.CURVE:
                return projectCurve(obs, batchId);
            case TvacConst.RecordType.FRAME:
                return projectFrame(obs, batchId);
            case TvacConst.RecordType.REPORT:
                return projectReport(obs, batchId);
            default:
                throw new RejectException("未知记录类型: " + obs.getRecordType());
        }
    }

    private Outcome projectCurve(TvacObservation obs, Long batchId) {
        TvacPlan plan = planMapper.selectById(obs.getPlanId());
        ensurePlanWritable(plan);
        String locked = lockGuard.lockedReason(plan.getId());
        if (locked != null) {
            throw new RejectException(locked);
        }
        int cycle = obs.getCycleNo() == null ? 1 : obs.getCycleNo();
        int offset = obs.getOffsetSec() == null ? 0 : obs.getOffsetSec();

        TvacCurvePoint existing = curvePointMapper.selectOne(new QueryWrapper<TvacCurvePoint>()
                .eq("plan_id", plan.getId())
                .eq("cycle_no", cycle)
                .eq("offset_sec", offset)
                .orderByAsc("id").last("LIMIT 1"));

        Long targetId;
        String result;
        if (existing == null) {
            TvacCurvePoint point = new TvacCurvePoint();
            point.setPlanId(plan.getId());
            point.setArticleId(plan.getArticleId());
            point.setCycleNo(cycle);
            point.setOffsetSec(offset);
            point.setPointTime(obs.getObserveTime());
            point.setTemperatureC(obs.getTemperatureC());
            point.setPressurePa(obs.getPressurePa());
            curvePointMapper.insert(point);
            targetId = point.getId();
            result = TvacConst.ImportResult.SUCCESS;
        } else {
            existing.setPointTime(obs.getObserveTime());
            existing.setTemperatureC(obs.getTemperatureC());
            existing.setPressurePa(obs.getPressurePa());
            curvePointMapper.updateById(existing);
            targetId = existing.getId();
            result = TvacConst.ImportResult.UPDATED;
        }
        upsertObservation(obs, batchId, "t_tvac_curve_point", targetId, result);
        return new Outcome(result, "t_tvac_curve_point", targetId);
    }

    private Outcome projectFrame(TvacObservation obs, Long batchId) {
        TvacPlan plan = planMapper.selectById(obs.getPlanId());
        ensurePlanWritable(plan);
        String locked = lockGuard.lockedReason(plan.getId());
        if (locked != null) {
            throw new RejectException(locked);
        }
        TvacChannel channel = channelMapper.selectById(obs.getChannelId());
        if (channel == null) {
            throw new RejectException("通道不存在: " + obs.getChannelCode());
        }
        if (!channel.getArticleId().equals(plan.getArticleId())) {
            throw new RejectException("通道不属于本计划的试验件，拒绝收帧");
        }
        if (TvacConst.ChannelStatus.DISABLED.equals(channel.getStatus())) {
            throw new RejectException("通道已停用，不能再导入遥测帧: " + channel.getChannelCode());
        }

        TvacTmFrame existing = frameMapper.selectOne(
                new QueryWrapper<TvacTmFrame>().eq("frame_seq", obs.getFrameSeq()));
        String limitFlag = limitJudge.judge(obs.getEngValue(), channel);
        Long targetId;
        String result;
        if (existing == null) {
            TvacTmFrame frame = new TvacTmFrame();
            frame.setFrameSeq(obs.getFrameSeq());
            frame.setPlanId(plan.getId());
            frame.setChannelId(channel.getId());
            frame.setArticleId(plan.getArticleId());
            frame.setCycleNo(obs.getCycleNo() == null ? 1 : obs.getCycleNo());
            frame.setFrameTime(obs.getObserveTime() == null ? LocalDateTime.now() : obs.getObserveTime());
            frame.setRawValue(obs.getRawValue());
            frame.setEngValue(obs.getEngValue());
            frame.setLimitFlag(limitFlag);
            frame.setSourceDevice(obs.getSourceDevice());
            frameMapper.insert(frame);
            targetId = frame.getId();
            result = TvacConst.ImportResult.SUCCESS;
        } else {
            // 业务键帧号已被其他计划/通道占用：冲突，拒绝覆盖
            if (!existing.getPlanId().equals(plan.getId())
                    || !existing.getChannelId().equals(channel.getId())) {
                throw new RejectException("帧号 " + obs.getFrameSeq()
                        + " 已被其他计划/通道占用，业务键冲突，拒绝覆盖");
            }
            existing.setCycleNo(obs.getCycleNo() == null ? existing.getCycleNo() : obs.getCycleNo());
            if (obs.getObserveTime() != null) {
                existing.setFrameTime(obs.getObserveTime());
            }
            existing.setRawValue(obs.getRawValue());
            existing.setEngValue(obs.getEngValue());
            existing.setLimitFlag(limitFlag);
            existing.setSourceDevice(obs.getSourceDevice());
            frameMapper.updateById(existing);
            targetId = existing.getId();
            result = TvacConst.ImportResult.UPDATED;
        }
        upsertObservation(obs, batchId, "t_tvac_tm_frame", targetId, result);
        return new Outcome(result, "t_tvac_tm_frame", targetId);
    }

    private Outcome projectReport(TvacObservation obs, Long batchId) {
        TvacPlan plan = planMapper.selectById(obs.getPlanId());
        if (plan == null) {
            throw new RejectException("计划不存在: " + obs.getPlanCode());
        }
        TvacReport report = reportMapper.selectOne(
                new QueryWrapper<TvacReport>().eq("plan_id", plan.getId()));

        Long targetId;
        String result;
        if (report == null) {
            if (!TvacConst.PlanStatus.COMPLETED.equals(plan.getStatus())) {
                throw new RejectException("计划尚未完成（" + plan.getStatus()
                        + "），不能导入判读结论: " + plan.getPlanCode());
            }
            report = new TvacReport();
            // 报告编号按计划确定性生成，保证失败重试不重复建档
            report.setReportNo("IMP-" + plan.getPlanCode());
            report.setPlanId(plan.getId());
            report.setConclusion(TvacConst.Conclusion.PENDING);
            report.setActualCycles(0);
            report.setTotalFrames(0);
            report.setAbnormalFrames(0);
            reportMapper.insert(report);
            result = TvacConst.ImportResult.SUCCESS;
        } else {
            if (report.getAcceptedTime() != null || report.getPostedTime() != null) {
                throw new RejectException("判读报告已验收或已落账，结论锁定，不得覆盖（报告: "
                        + report.getReportNo() + "）");
            }
            result = TvacConst.ImportResult.UPDATED;
        }
        aggregateAndJudge(report, obs);
        targetId = report.getId();
        upsertObservation(obs, batchId, "t_tvac_report", targetId, result);
        return new Outcome(result, "t_tvac_report", targetId);
    }

    /** 复用判读汇总口径：循环次数/帧数/异常帧/温压极值 + 写入结论 */
    private void aggregateAndJudge(TvacReport report, TvacObservation obs) {
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
        report.setConclusion(obs.getConclusion());
        report.setJudgedBy("IMPORT");
        report.setJudgedTime(LocalDateTime.now());
        reportMapper.updateById(report);
    }

    private void ensurePlanWritable(TvacPlan plan) {
        if (plan == null) {
            throw new RejectException("试验计划不存在");
        }
        String status = plan.getStatus();
        if (TvacConst.PlanStatus.DRAFT.equals(status) || TvacConst.PlanStatus.ISSUED.equals(status)
                || TvacConst.PlanStatus.TERMINATED.equals(status)) {
            throw new RejectException("计划当前状态 " + status + "，不允许导入观测数据（计划: "
                    + plan.getPlanCode() + "）");
        }
    }

    /** 归一化台账按 (record_type, biz_key) 幂等落库 */
    private void upsertObservation(TvacObservation obs, Long batchId,
                                   String targetTable, Long targetId, String result) {
        TvacObservation ledger = observationMapper.selectOne(new QueryWrapper<TvacObservation>()
                .eq("record_type", obs.getRecordType())
                .eq("biz_key", obs.getBizKey()));
        obs.setId(ledger == null ? null : ledger.getId());
        obs.setBatchId(batchId);
        obs.setTargetTable(targetTable);
        obs.setTargetId(targetId);
        obs.setStatus(TvacConst.ImportResult.UPDATED.equals(result) ? "UPDATED" : "IMPORTED");
        if (ledger == null) {
            observationMapper.insert(obs);
        } else {
            observationMapper.updateById(obs);
        }
    }
}
