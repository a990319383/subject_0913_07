package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.CalcRunCreateRequest;
import com.evops.entity.TvacCalcDetail;
import com.evops.entity.TvacCalcRun;
import com.evops.entity.TvacObservation;
import com.evops.entity.TvacRuleBand;
import com.evops.entity.TvacRuleSet;
import com.evops.entity.TvacRuleVersion;
import com.evops.mapper.TvacCalcDetailMapper;
import com.evops.mapper.TvacCalcRunMapper;
import com.evops.mapper.TvacObservationMapper;
import com.evops.mapper.TvacRuleBandMapper;
import com.evops.mapper.TvacRuleSetMapper;
import com.evops.mapper.TvacRuleVersionMapper;
import com.evops.util.BandWindows;
import com.evops.vo.CalcRunDetailVo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时序规则计算：把窗口内的遥测观测（设备 UTC 为源）按对象任务时区
 * 归入峰值/平段/谷值业务区间，生成计算批次与逐区间明细。
 *
 * <p>要点：
 * <ul>
 *   <li>区间左闭右开、支持业务时区跨日（见 {@link BandWindows}）；</li>
 *   <li>计算批次以（规则版本 + UTC 窗口）为幂等键，重复提交返回原批次；
 *       并发重算由数据库唯一约束兜底，不会产生两份结果；</li>
 *   <li>批次落库时把采用的规则区间写入快照，历史结果读取以快照为准，
 *       规则换版不污染既有批次与已签发报告；</li>
 *   <li>累计一律 BigDecimal 全精度累加，均值仅在最终步骤统一舍入。</li>
 * </ul>
 */
@Service
public class TvacCalcService {

    /** 均值最终统一舍入标度（累计过程不舍入） */
    public static final int AVG_SCALE = 4;

    private final TvacCalcRunMapper calcRunMapper;
    private final TvacCalcDetailMapper calcDetailMapper;
    private final TvacRuleSetMapper ruleSetMapper;
    private final TvacRuleVersionMapper ruleVersionMapper;
    private final TvacRuleBandMapper ruleBandMapper;
    private final TvacObservationMapper observationMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public TvacCalcService(TvacCalcRunMapper calcRunMapper,
                           TvacCalcDetailMapper calcDetailMapper,
                           TvacRuleSetMapper ruleSetMapper,
                           TvacRuleVersionMapper ruleVersionMapper,
                           TvacRuleBandMapper ruleBandMapper,
                           TvacObservationMapper observationMapper,
                           org.springframework.transaction.PlatformTransactionManager txManager,
                           ObjectMapper objectMapper) {
        this.calcRunMapper = calcRunMapper;
        this.calcDetailMapper = calcDetailMapper;
        this.ruleSetMapper = ruleSetMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.ruleBandMapper = ruleBandMapper;
        this.observationMapper = observationMapper;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 执行区间计算（幂等）：同一（规则版本 + UTC 窗口）重复提交返回原批次；
     * 并发下唯一约束只放行一个批次，其余请求改道读取已落库批次。
     */
    public CalcRunDetailVo execute(CalcRunCreateRequest req) {
        if (req.getWindowStartUtc() == null || req.getWindowEndUtc() == null
                || !req.getWindowStartUtc().isBefore(req.getWindowEndUtc())) {
            throw BusinessException.of("计算窗口必须为左闭右开且起点早于终点（设备 UTC）");
        }
        TvacRuleSet set = ruleSetMapper.selectById(req.getSetId());
        if (set == null) {
            throw BusinessException.of("规则集不存在: " + req.getSetId());
        }
        TvacRuleVersion version = resolveVersion(set, req.getVersionId());
        List<TvacRuleBand> bands = ruleBandMapper.selectList(
                new QueryWrapper<TvacRuleBand>()
                        .eq("version_id", version.getId()).orderByAsc("id"));
        if (bands.isEmpty()) {
            throw BusinessException.of("规则版本未定义业务区间，不能计算: 版本 " + version.getVersionNo());
        }

        TvacCalcRun existing = findByScope(version.getId(),
                req.getWindowStartUtc(), req.getWindowEndUtc());
        if (existing != null) {
            return detail(existing.getId());
        }

        try {
            TvacCalcRun run = transactionTemplate.execute(status ->
                    doExecute(set, version, bands, req.getWindowStartUtc(), req.getWindowEndUtc()));
            return detail(run.getId());
        } catch (DuplicateKeyException e) {
            // 并发重算：唯一约束只放行一个批次，本请求改读已落库的那一份
            TvacCalcRun won = findByScope(version.getId(),
                    req.getWindowStartUtc(), req.getWindowEndUtc());
            if (won != null) {
                return detail(won.getId());
            }
            throw e;
        }
    }

    /** 批次详情：运行头 + 逐区间明细，窗口时间同时给出任务时区展示 */
    public CalcRunDetailVo detail(Long runId) {
        TvacCalcRun run = calcRunMapper.selectById(runId);
        if (run == null) {
            throw BusinessException.of("计算批次不存在: " + runId);
        }
        List<TvacCalcDetail> details = calcDetailMapper.selectList(
                new QueryWrapper<TvacCalcDetail>()
                        .eq("run_id", runId).orderByAsc("id"));
        ZoneId zone = ZoneId.of(run.getMissionTz());
        CalcRunDetailVo vo = new CalcRunDetailVo();
        vo.setRun(run);
        vo.setDetails(details);
        vo.setMissionTz(run.getMissionTz());
        vo.setWindowStartMission(BandWindows.toMissionTime(run.getWindowStartUtc(), zone));
        vo.setWindowEndMission(BandWindows.toMissionTime(run.getWindowEndUtc(), zone));
        return vo;
    }

    public List<TvacCalcRun> list(Long setId, Long articleId) {
        QueryWrapper<TvacCalcRun> qw = new QueryWrapper<>();
        if (setId != null) {
            qw.eq("set_id", setId);
        }
        if (articleId != null) {
            qw.eq("article_id", articleId);
        }
        qw.orderByDesc("id");
        return calcRunMapper.selectList(qw);
    }

    // ---------------- 内部实现 ----------------

    /** 解析计算采用的规则版本：显式指定须属于该集且已启用；缺省取集内当前启用版 */
    private TvacRuleVersion resolveVersion(TvacRuleSet set, Long versionId) {
        if (versionId != null) {
            TvacRuleVersion version = ruleVersionMapper.selectById(versionId);
            if (version == null || !version.getSetId().equals(set.getId())) {
                throw BusinessException.of("规则版本不属于规则集 " + set.getSetCode()
                        + ": " + versionId);
            }
            if (!TvacConst.RuleVersionStatus.ENABLED.equals(version.getStatus())) {
                throw BusinessException.of("只有启用状态的规则版本才能参与计算，当前状态: "
                        + version.getStatus());
            }
            return version;
        }
        TvacRuleVersion enabled = ruleVersionMapper.selectOne(
                new QueryWrapper<TvacRuleVersion>()
                        .eq("set_id", set.getId())
                        .eq("status", TvacConst.RuleVersionStatus.ENABLED)
                        .orderByDesc("version_no").last("LIMIT 1"));
        if (enabled == null) {
            throw BusinessException.of("规则集当前没有启用中的规则版本: " + set.getSetCode());
        }
        return enabled;
    }

    private TvacCalcRun findByScope(Long versionId, LocalDateTime windowStartUtc,
                                    LocalDateTime windowEndUtc) {
        return calcRunMapper.selectOne(new QueryWrapper<TvacCalcRun>()
                .eq("version_id", versionId)
                .eq("window_start_utc", windowStartUtc)
                .eq("window_end_utc", windowEndUtc));
    }

    /** 单事务内完成：归类累计 -> 批次 + 明细落库（快照随批次留痕） */
    private TvacCalcRun doExecute(TvacRuleSet set, TvacRuleVersion version,
                                  List<TvacRuleBand> bands,
                                  LocalDateTime windowStartUtc, LocalDateTime windowEndUtc) {
        ZoneId zone = ZoneId.of(set.getMissionTz());
        List<TvacObservation> observations = observationMapper.selectList(
                new QueryWrapper<TvacObservation>()
                        .eq("article_id", set.getArticleId())
                        .eq("record_type", TvacConst.RecordType.FRAME)
                        .in("status", "IMPORTED", "UPDATED")
                        .isNotNull("eng_value")
                        .isNotNull("observe_time")
                        .ge("observe_time", windowStartUtc)
                        .lt("observe_time", windowEndUtc)
                        .orderByAsc("id"));

        // 归类：UTC -> 任务时区当日分钟 -> 命中唯一区间（区间互不重叠，至多命中一个）
        Map<Long, Acc> accByBand = new LinkedHashMap<>();
        for (TvacRuleBand band : bands) {
            accByBand.put(band.getId(), new Acc());
        }
        for (TvacObservation obs : observations) {
            int minute = BandWindows.minuteOfDay(obs.getObserveTime(), zone);
            for (TvacRuleBand band : bands) {
                if (BandWindows.contains(band.getStartMin(), band.getEndMin(), minute)) {
                    accByBand.get(band.getId()).add(obs.getEngValue());
                    break;
                }
            }
        }

        TvacCalcRun run = new TvacCalcRun();
        run.setRunNo(buildRunNo(set.getId(), version.getVersionNo(),
                windowStartUtc, windowEndUtc));
        run.setSetId(set.getId());
        run.setVersionId(version.getId());
        run.setVersionNo(version.getVersionNo());
        run.setArticleId(set.getArticleId());
        run.setWindowStartUtc(windowStartUtc);
        run.setWindowEndUtc(windowEndUtc);
        run.setMissionTz(set.getMissionTz());
        run.setSnapshotJson(snapshotOf(version, bands));
        run.setObsCount(observations.size());
        run.setStatus(TvacConst.CalcRunStatus.DONE);
        calcRunMapper.insert(run);

        for (TvacRuleBand band : bands) {
            Acc acc = accByBand.get(band.getId());
            TvacCalcDetail detail = new TvacCalcDetail();
            detail.setRunId(run.getId());
            detail.setBandId(band.getId());
            detail.setBandType(band.getBandType());
            detail.setStartMin(band.getStartMin());
            detail.setEndMin(band.getEndMin());
            detail.setObsCount(acc.count);
            if (acc.count > 0) {
                detail.setSumValue(acc.sum);
                detail.setMinValue(acc.min);
                detail.setMaxValue(acc.max);
                // 均值仅在最终步骤统一舍入；累计过程保持 BigDecimal 全精度
                detail.setAvgValue(acc.sum.divide(
                        BigDecimal.valueOf(acc.count), AVG_SCALE, RoundingMode.HALF_UP));
            }
            calcDetailMapper.insert(detail);
        }
        return run;
    }

    /** 批次号按范围确定性生成：同范围重算得到同一批次号 */
    private String buildRunNo(Long setId, Integer versionNo,
                              LocalDateTime windowStartUtc, LocalDateTime windowEndUtc) {
        return "CR" + setId + "V" + versionNo + "-"
                + windowStartUtc.toEpochSecond(ZoneOffset.UTC) + "-"
                + windowEndUtc.toEpochSecond(ZoneOffset.UTC);
    }

    /** 采用的规则区间快照：历史结果读取以快照为准，换版不污染 */
    private String snapshotOf(TvacRuleVersion version, List<TvacRuleBand> bands) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TvacRuleBand b : bands) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bandId", b.getId());
            item.put("bandType", b.getBandType());
            item.put("startMin", b.getStartMin());
            item.put("endMin", b.getEndMin());
            items.add(item);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("versionId", version.getId());
        snapshot.put("versionNo", version.getVersionNo());
        snapshot.put("bands", items);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw BusinessException.of("规则快照序列化失败: " + e.getMessage());
        }
    }

    /** 单区间累计器：BigDecimal 全精度累加，不在中间步骤舍入 */
    private static final class Acc {
        private int count;
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal min;
        private BigDecimal max;

        void add(BigDecimal value) {
            count++;
            sum = sum.add(value);
            min = min == null || value.compareTo(min) < 0 ? value : min;
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
    }
}
