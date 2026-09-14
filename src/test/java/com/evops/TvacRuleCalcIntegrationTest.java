package com.evops;

import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.ArticleCreateRequest;
import com.evops.dto.CalcRunCreateRequest;
import com.evops.dto.PlanCreateRequest;
import com.evops.dto.ReportAcceptRequest;
import com.evops.dto.ReportBindCalcRequest;
import com.evops.dto.ReportCreateRequest;
import com.evops.dto.ReportJudgeRequest;
import com.evops.dto.RuleBandRequest;
import com.evops.dto.RuleSetCreateRequest;
import com.evops.dto.RuleVersionCreateRequest;
import com.evops.entity.TvacObservation;
import com.evops.entity.TvacReport;
import com.evops.entity.TvacRuleSet;
import com.evops.entity.TvacRuleVersion;
import com.evops.mapper.TvacObservationMapper;
import com.evops.service.TvacArticleService;
import com.evops.service.TvacCalcService;
import com.evops.service.TvacPlanService;
import com.evops.service.TvacReportService;
import com.evops.service.TvacRuleService;
import com.evops.vo.CalcRunDetailVo;
import com.evops.vo.RuleVersionVo;
import com.evops.entity.TvacCalcDetail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时序判读规则与区间计算（tvac-4）集成测试：
 * 规则集/版本维护与重叠拒绝、启用后不可原地修改、业务时区跨日归类、
 * 左闭右开边界、BigDecimal 累计最终统一舍入、4 个版本化规则快照留痕、
 * 重算幂等、已签发报告不被换版污染。每个测试方法事务回滚。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TvacRuleCalcIntegrationTest {

    @Autowired
    private TvacRuleService ruleService;
    @Autowired
    private TvacCalcService calcService;
    @Autowired
    private TvacArticleService articleService;
    @Autowired
    private TvacPlanService planService;
    @Autowired
    private TvacReportService reportService;
    @Autowired
    private TvacObservationMapper observationMapper;

    // ---------------- 夹具 ----------------

    private long newArticle(String code) {
        ArticleCreateRequest a = new ArticleCreateRequest();
        a.setArticleCode(code);
        a.setArticleName("试验件" + code);
        a.setBatchNo("B-RC");
        return articleService.create(a).getId();
    }

    private long newSet(String code, long articleId, String missionTz) {
        RuleSetCreateRequest req = new RuleSetCreateRequest();
        req.setSetCode(code);
        req.setSetName("时序规则" + code);
        req.setArticleId(articleId);
        req.setMissionTz(missionTz);
        return ruleService.createSet(req).getId();
    }

    private RuleBandRequest band(String type, int startMin, int endMin) {
        RuleBandRequest b = new RuleBandRequest();
        b.setBandType(type);
        b.setStartMin(startMin);
        b.setEndMin(endMin);
        return b;
    }

    private RuleVersionCreateRequest versionReq(RuleBandRequest... bands) {
        RuleVersionCreateRequest req = new RuleVersionCreateRequest();
        req.setBands(Arrays.asList(bands));
        return req;
    }

    private void obs(String bizKey, long articleId, String utcTime, String engValue) {
        TvacObservation o = new TvacObservation();
        o.setRecordType(TvacConst.RecordType.FRAME);
        o.setBizKey(bizKey);
        o.setArticleId(articleId);
        o.setFrameSeq("FS-" + bizKey);
        o.setObserveTime(LocalDateTime.parse(utcTime));
        o.setEngValue(new BigDecimal(engValue));
        o.setStatus("IMPORTED");
        observationMapper.insert(o);
    }

    private CalcRunCreateRequest calcReq(long setId, Long versionId, String startUtc, String endUtc) {
        CalcRunCreateRequest req = new CalcRunCreateRequest();
        req.setSetId(setId);
        req.setVersionId(versionId);
        req.setWindowStartUtc(LocalDateTime.parse(startUtc));
        req.setWindowEndUtc(LocalDateTime.parse(endUtc));
        return req;
    }

    private TvacCalcDetail detailOf(CalcRunDetailVo vo, String bandType) {
        return vo.getDetails().stream()
                .filter(d -> bandType.equals(d.getBandType()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少区间明细: " + bandType));
    }

    private static void assertBd(String expected, BigDecimal actual) {
        assertNotNull(actual, "数值不应为空，期望 " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + actual);
    }

    // ---------------- 规则维护 ----------------

    @Test
    void ruleSetAndVersionCrudWithOverlapRejection() {
        long articleId = newArticle("RC-CRUD-1");

        // 非法任务时区、对象不存在：拒绝建档
        RuleSetCreateRequest badTz = new RuleSetCreateRequest();
        badTz.setSetCode("RS-BAD-TZ");
        badTz.setSetName("x");
        badTz.setArticleId(articleId);
        badTz.setMissionTz("Mars/Olympus");
        assertThrows(BusinessException.class, () -> ruleService.createSet(badTz));
        RuleSetCreateRequest noArticle = new RuleSetCreateRequest();
        noArticle.setSetCode("RS-NO-ART");
        noArticle.setSetName("x");
        noArticle.setArticleId(-1L);
        noArticle.setMissionTz("Asia/Shanghai");
        assertThrows(BusinessException.class, () -> ruleService.createSet(noArticle));

        long setId = newSet("RS-CRUD-1", articleId, "Asia/Shanghai");
        TvacRuleSet set = ruleService.getSet(setId);
        assertEquals("Asia/Shanghai", set.getMissionTz());
        assertEquals(1, ruleService.listSets(articleId).size());

        // 相邻区间（左闭右开端点相接）不算重叠；同类型可出现多段
        RuleVersionVo v1 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 600),
                band(TvacConst.BandType.FLAT, 600, 1320),
                band(TvacConst.BandType.VALLEY, 1320, 1440)));
        assertEquals(1, v1.getVersion().getVersionNo());
        assertEquals(TvacConst.RuleVersionStatus.DRAFT, v1.getVersion().getStatus());
        assertEquals(3, v1.getBands().size());

        RuleVersionVo v2 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 360),
                band(TvacConst.BandType.PEAK, 720, 1080)));
        assertEquals(2, v2.getVersion().getVersionNo());

        // 区间重叠必须拒绝：线性重叠
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> ruleService.createVersion(setId, versionReq(
                        band(TvacConst.BandType.PEAK, 600, 960),
                        band(TvacConst.BandType.FLAT, 900, 1200))));
        assertTrue(ex1.getMessage().contains("重叠"));
        // 跨日重叠：VALLEY[1320,600) 与 PEAK[500,700) 在凌晨段相交
        assertThrows(BusinessException.class,
                () -> ruleService.createVersion(setId, versionReq(
                        band(TvacConst.BandType.VALLEY, 1320, 600),
                        band(TvacConst.BandType.PEAK, 500, 700))));
        // 非法端点：起止相同 / 起点越界 / 终点越界 / 未知类型
        assertThrows(BusinessException.class,
                () -> ruleService.createVersion(setId, versionReq(band(TvacConst.BandType.PEAK, 600, 600))));
        assertThrows(BusinessException.class,
                () -> ruleService.createVersion(setId, versionReq(band(TvacConst.BandType.PEAK, 1440, 10))));
        assertThrows(BusinessException.class,
                () -> ruleService.createVersion(setId, versionReq(band(TvacConst.BandType.PEAK, 0, 1441))));
        assertThrows(BusinessException.class,
                () -> ruleService.createVersion(setId, versionReq(band("MID", 0, 600))));
    }

    @Test
    void enabledVersionIsFrozenAndSingleEnabledPerSet() {
        long articleId = newArticle("RC-FRZ-1");
        long setId = newSet("RS-FRZ-1", articleId, "UTC");

        RuleVersionVo v1 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 720)));
        // DRAFT 可整体换区间
        RuleVersionVo replaced = ruleService.replaceBands(v1.getVersion().getId(),
                Arrays.asList(band(TvacConst.BandType.PEAK, 0, 720),
                        band(TvacConst.BandType.FLAT, 720, 1440)));
        assertEquals(2, replaced.getBands().size());

        ruleService.enable(v1.getVersion().getId());
        TvacRuleVersion enabled1 = ruleService.getVersion(v1.getVersion().getId()).getVersion();
        assertEquals(TvacConst.RuleVersionStatus.ENABLED, enabled1.getStatus());
        assertNotNull(enabled1.getEnabledTime());

        // 启用后不能原地修改区间；重复启用拒绝
        assertThrows(BusinessException.class, () -> ruleService.replaceBands(
                v1.getVersion().getId(),
                Arrays.asList(band(TvacConst.BandType.PEAK, 0, 100))));
        assertThrows(BusinessException.class, () -> ruleService.enable(v1.getVersion().getId()));

        // 启用 v2 后 v1 自动停用；已启用过的版本是历史档案，不能删除
        RuleVersionVo v2 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.VALLEY, 0, 720),
                band(TvacConst.BandType.PEAK, 720, 1440)));
        ruleService.enable(v2.getVersion().getId());
        assertEquals(TvacConst.RuleVersionStatus.DISABLED,
                ruleService.getVersion(v1.getVersion().getId()).getVersion().getStatus());
        assertThrows(BusinessException.class, () -> ruleService.deleteVersion(v1.getVersion().getId()));
        assertThrows(BusinessException.class, () -> ruleService.deleteVersion(v2.getVersion().getId()));

        // DRAFT 可删除；停用仅对启用版有效
        RuleVersionVo v3 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.FLAT, 0, 1440)));
        ruleService.deleteVersion(v3.getVersion().getId());
        assertThrows(BusinessException.class, () -> ruleService.getVersion(v3.getVersion().getId()));
        assertThrows(BusinessException.class, () -> ruleService.disable(v1.getVersion().getId()));
        ruleService.disable(v2.getVersion().getId());
        assertNull(ruleService.enabledVersionOf(setId));
    }

    // ---------------- 时区归类与计算 ----------------

    @Test
    void calcClassifiesUtcObservationsByMissionTzAcrossMidnight() {
        long articleId = newArticle("RC-TZ-1");
        long setId = newSet("RS-TZ-1", articleId, "Asia/Shanghai");
        // 峰值 10:00-16:00，平段 16:00-22:00，谷值 22:00-次日10:00（跨日）
        RuleVersionVo v1 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 600, 960),
                band(TvacConst.BandType.FLAT, 960, 1320),
                band(TvacConst.BandType.VALLEY, 1320, 600)));
        ruleService.enable(v1.getVersion().getId());

        // 观测以设备 UTC 为源；+8 换算到任务时区归区间
        obs("TZ-1", articleId, "2026-03-01T00:00", "4.0000");   // 08:00 谷值（窗口起点，含）
        obs("TZ-2", articleId, "2026-03-01T01:00", "1.0000");   // 09:00 谷值
        obs("TZ-3", articleId, "2026-03-01T02:00", "10.0001");  // 10:00 峰值（左闭）
        obs("TZ-4", articleId, "2026-03-01T07:59", "10.0002");  // 15:59 峰值
        obs("TZ-5", articleId, "2026-03-01T08:00", "20.0000");  // 16:00 平段（右开，不属峰值）
        obs("TZ-6", articleId, "2026-03-01T13:59", "22.0000");  // 21:59 平段
        obs("TZ-7", articleId, "2026-03-01T14:00", "3.0000");   // 22:00 谷值
        obs("TZ-8", articleId, "2026-03-01T16:30", "3.5000");   // 次日00:30 谷值（跨日）
        obs("TZ-9", articleId, "2026-03-02T00:00", "99.0000");  // 窗口终点（右开，不含）

        CalcRunDetailVo vo = calcService.execute(
                calcReq(setId, null, "2026-03-01T00:00", "2026-03-02T00:00"));

        assertEquals(8, vo.getRun().getObsCount());
        assertEquals(1, vo.getRun().getVersionNo());
        assertEquals(3, vo.getDetails().size());
        // 窗口按任务时区展示
        assertEquals(LocalDateTime.of(2026, 3, 1, 8, 0), vo.getWindowStartMission());
        assertEquals(LocalDateTime.of(2026, 3, 2, 8, 0), vo.getWindowEndMission());
        assertEquals("Asia/Shanghai", vo.getMissionTz());

        // 峰值：BigDecimal 全精度累计，均值最终一步舍入（10.00015 -> 10.0002）
        TvacCalcDetail peak = detailOf(vo, TvacConst.BandType.PEAK);
        assertEquals(2, peak.getObsCount());
        assertBd("20.0003", peak.getSumValue());
        assertBd("10.0001", peak.getMinValue());
        assertBd("10.0002", peak.getMaxValue());
        assertBd("10.0002", peak.getAvgValue());

        TvacCalcDetail flat = detailOf(vo, TvacConst.BandType.FLAT);
        assertEquals(2, flat.getObsCount());
        assertBd("42.0000", flat.getSumValue());
        assertBd("21.0000", flat.getAvgValue());

        // 谷值跨日：08:00/09:00/22:00/次日00:30 四个点
        TvacCalcDetail valley = detailOf(vo, TvacConst.BandType.VALLEY);
        assertEquals(4, valley.getObsCount());
        assertBd("11.5000", valley.getSumValue());
        assertBd("2.8750", valley.getAvgValue());
        assertEquals(1320, valley.getStartMin());
        assertEquals(600, valley.getEndMin());

        // 重算幂等：同范围再算返回同一批次，不产生第二份结果
        CalcRunDetailVo again = calcService.execute(
                calcReq(setId, null, "2026-03-01T00:00", "2026-03-02T00:00"));
        assertEquals(vo.getRun().getId(), again.getRun().getId());
        assertEquals(1, calcService.list(setId, null).size());
    }

    @Test
    void calcWindowAndVersionGuards() {
        long articleId = newArticle("RC-GUARD-1");
        long setId = newSet("RS-GUARD-1", articleId, "UTC");
        RuleVersionVo v1 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 720)));

        // 窗口必须左闭右开且起点早于终点
        assertThrows(BusinessException.class, () -> calcService.execute(
                calcReq(setId, null, "2026-03-01T00:00", "2026-03-01T00:00")));
        assertThrows(BusinessException.class, () -> calcService.execute(
                calcReq(setId, null, "2026-03-02T00:00", "2026-03-01T00:00")));
        // 无启用版本 / DRAFT 版本不能参与计算
        assertThrows(BusinessException.class, () -> calcService.execute(
                calcReq(setId, null, "2026-03-01T00:00", "2026-03-02T00:00")));
        assertThrows(BusinessException.class, () -> calcService.execute(
                calcReq(setId, v1.getVersion().getId(), "2026-03-01T00:00", "2026-03-02T00:00")));
        // 规则集不存在
        assertThrows(BusinessException.class, () -> calcService.execute(
                calcReq(-1L, null, "2026-03-01T00:00", "2026-03-02T00:00")));
    }

    @Test
    void fourVersionedRulesKeepSnapshotsAndIssuedReportIsNotPolluted() {
        long articleId = newArticle("RC-VER-1");
        long planId = newCompletedPlan("PL-RC-VER-1", articleId);
        long setId = newSet("RS-VER-1", articleId, "UTC");

        // 窗口内 4 条遥测（UTC）：01:00/06:00/12:00/18:00
        obs("V-1", articleId, "2026-05-01T01:00", "1.0000");
        obs("V-2", articleId, "2026-05-01T06:00", "2.0000");
        obs("V-3", articleId, "2026-05-01T12:00", "3.0000");
        obs("V-4", articleId, "2026-05-01T18:00", "4.0000");
        String ws = "2026-05-01T00:00";
        String we = "2026-05-02T00:00";

        // 4 个版本化规则：逐版启用、逐版计算，历史批次各自留痕
        RuleVersionVo v1 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 720),
                band(TvacConst.BandType.FLAT, 720, 1440)));
        ruleService.enable(v1.getVersion().getId());
        CalcRunDetailVo run1 = calcService.execute(calcReq(setId, null, ws, we));

        RuleVersionVo v2 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 360),
                band(TvacConst.BandType.FLAT, 360, 1080),
                band(TvacConst.BandType.VALLEY, 1080, 1440)));
        ruleService.enable(v2.getVersion().getId());
        CalcRunDetailVo run2 = calcService.execute(calcReq(setId, null, ws, we));

        // v3 区间不覆盖全天：06:00 与 18:00 两点不落任何区间（计入批次总数，不进明细）
        RuleVersionVo v3 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.PEAK, 0, 360),
                band(TvacConst.BandType.FLAT, 720, 1080)));
        ruleService.enable(v3.getVersion().getId());
        CalcRunDetailVo run3 = calcService.execute(calcReq(setId, null, ws, we));

        RuleVersionVo v4 = ruleService.createVersion(setId, versionReq(
                band(TvacConst.BandType.VALLEY, 0, 720),
                band(TvacConst.BandType.PEAK, 720, 1440)));
        ruleService.enable(v4.getVersion().getId());
        CalcRunDetailVo run4 = calcService.execute(calcReq(setId, null, ws, we));

        // 4 个批次、版本号各自对应
        assertEquals(1, run1.getRun().getVersionNo());
        assertEquals(2, run2.getRun().getVersionNo());
        assertEquals(3, run3.getRun().getVersionNo());
        assertEquals(4, run4.getRun().getVersionNo());
        assertEquals(4, calcService.list(setId, null).size());

        // v1：峰值[0,720) 收 01:00/06:00，平段[720,1440) 收 12:00/18:00
        assertEquals(2, detailOf(run1, TvacConst.BandType.PEAK).getObsCount());
        assertBd("3.0000", detailOf(run1, TvacConst.BandType.PEAK).getSumValue());
        assertEquals(2, detailOf(run1, TvacConst.BandType.FLAT).getObsCount());
        assertBd("7.0000", detailOf(run1, TvacConst.BandType.FLAT).getSumValue());
        // v2：三段各归各位
        assertEquals(1, detailOf(run2, TvacConst.BandType.PEAK).getObsCount());
        assertEquals(2, detailOf(run2, TvacConst.BandType.FLAT).getObsCount());
        assertEquals(1, detailOf(run2, TvacConst.BandType.VALLEY).getObsCount());
        // v3：部分覆盖，未归类观测只计入批次总数
        assertEquals(4, run3.getRun().getObsCount());
        assertEquals(1, detailOf(run3, TvacConst.BandType.PEAK).getObsCount());
        assertEquals(1, detailOf(run3, TvacConst.BandType.FLAT).getObsCount());
        // v4：谷值/峰值互换
        assertEquals(2, detailOf(run4, TvacConst.BandType.VALLEY).getObsCount());
        assertEquals(2, detailOf(run4, TvacConst.BandType.PEAK).getObsCount());

        // 历史结果读取当时快照：v1 批次仍是 v1 的区间与版本号，换版不污染
        CalcRunDetailVo run1Again = calcService.detail(run1.getRun().getId());
        assertEquals(1, run1Again.getRun().getVersionNo());
        assertEquals(2, run1Again.getDetails().size());
        assertTrue(run1Again.getRun().getSnapshotJson().contains("\"versionNo\":1"));
        assertTrue(run1Again.getRun().getSnapshotJson().contains("\"startMin\":0"));
        // 换版后用旧版本显式重算：版本已停用，拒绝新算，但历史批次永远可读
        assertThrows(BusinessException.class, () -> calcService.execute(
                calcReq(setId, v1.getVersion().getId(), ws, we)));

        // 判读报告绑定计算批次：签发（验收/落账）后绑定锁定，换版不污染已签发报告
        ReportCreateRequest rc = new ReportCreateRequest();
        rc.setReportNo("RPT-RC-VER-1");
        rc.setPlanId(planId);
        long reportId = reportService.create(rc).getId();
        ReportJudgeRequest judge = new ReportJudgeRequest();
        judge.setConclusion(TvacConst.Conclusion.QUALIFIED);
        judge.setJudgedBy("张工");
        reportService.judge(reportId, judge);

        // 试验件不一致的批次不能绑定
        long otherArticle = newArticle("RC-VER-2");
        long otherSet = newSet("RS-VER-2", otherArticle, "UTC");
        RuleVersionVo ov1 = ruleService.createVersion(otherSet, versionReq(
                band(TvacConst.BandType.PEAK, 0, 1440)));
        ruleService.enable(ov1.getVersion().getId());
        CalcRunDetailVo foreignRun = calcService.execute(calcReq(otherSet, null, ws, we));
        ReportBindCalcRequest badBind = new ReportBindCalcRequest();
        badBind.setRunId(foreignRun.getRun().getId());
        assertThrows(BusinessException.class, () -> reportService.bindCalc(reportId, badBind));

        ReportBindCalcRequest bind = new ReportBindCalcRequest();
        bind.setRunId(run1.getRun().getId());
        reportService.bindCalc(reportId, bind);
        TvacReport bound = reportService.getById(reportId);
        assertEquals(run1.getRun().getId(), bound.getCalcRunId());
        assertEquals(v1.getVersion().getId(), bound.getRuleVersionId());

        ReportAcceptRequest accept = new ReportAcceptRequest();
        accept.setAcceptedBy("王总");
        reportService.accept(reportId, accept);
        // 已验收（签发）：换绑被拒绝，v4 批次再新也不影响报告引用的 v1 快照
        ReportBindCalcRequest rebind = new ReportBindCalcRequest();
        rebind.setRunId(run4.getRun().getId());
        assertThrows(BusinessException.class, () -> reportService.bindCalc(reportId, rebind));
        reportService.post(reportId);
        TvacReport posted = reportService.getById(reportId);
        assertEquals(run1.getRun().getId(), posted.getCalcRunId());
        assertEquals(v1.getVersion().getId(), posted.getRuleVersionId());
    }

    private long newCompletedPlan(String code, long articleId) {
        PlanCreateRequest p = new PlanCreateRequest();
        p.setPlanCode(code);
        p.setPlanName("热真空" + code);
        p.setArticleId(articleId);
        p.setHighTempC(new BigDecimal("70"));
        p.setLowTempC(new BigDecimal("-65"));
        p.setTargetCycles(1);
        long planId = planService.create(p).getId();
        planService.changeStatus(planId, TvacConst.PlanStatus.ISSUED);
        planService.changeStatus(planId, TvacConst.PlanStatus.RUNNING);
        planService.changeStatus(planId, TvacConst.PlanStatus.COMPLETED);
        return planId;
    }
}
