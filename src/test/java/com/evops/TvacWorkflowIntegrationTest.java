package com.evops;

import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.ArticleBatchCreateRequest;
import com.evops.dto.ChannelCreateRequest;
import com.evops.dto.CurvePointCreateRequest;
import com.evops.dto.FrameCreateRequest;
import com.evops.dto.PlanCreateRequest;
import com.evops.dto.ReportAcceptRequest;
import com.evops.dto.ReportCreateRequest;
import com.evops.dto.ReportJudgeRequest;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacReport;
import com.evops.entity.TvacTmFrame;
import com.evops.service.TvacArticleService;
import com.evops.service.TvacChannelService;
import com.evops.service.TvacCurvePointService;
import com.evops.service.TvacPlanService;
import com.evops.service.TvacReportService;
import com.evops.service.TvacTmFrameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热真空试验核心闭环集成测试：批次建档 -> 计划状态流转 ->
 * 曲线/遥测录入与越限判读 -> 判读报告自动汇总 -> 验收/落账 -> 删除保护。
 * 每个测试方法事务回滚，不污染 H2 文件库。
 */
@SpringBootTest
@Transactional
class TvacWorkflowIntegrationTest {

    @Autowired
    private TvacArticleService articleService;
    @Autowired
    private TvacPlanService planService;
    @Autowired
    private TvacChannelService channelService;
    @Autowired
    private TvacCurvePointService curvePointService;
    @Autowired
    private TvacTmFrameService frameService;
    @Autowired
    private TvacReportService reportService;

    private long newArticle(String code, String batchNo) {
        com.evops.dto.ArticleCreateRequest a = new com.evops.dto.ArticleCreateRequest();
        a.setArticleCode(code);
        a.setArticleName("试验件" + code);
        a.setBatchNo(batchNo);
        return articleService.create(a).getId();
    }

    private long newPlan(String code, long articleId) {
        PlanCreateRequest p = new PlanCreateRequest();
        p.setPlanCode(code);
        p.setPlanName("热真空" + code);
        p.setArticleId(articleId);
        p.setHighTempC(new BigDecimal("70"));
        p.setLowTempC(new BigDecimal("-65"));
        p.setVacuumPa(new BigDecimal("0.001"));
        p.setTargetCycles(3);
        return planService.create(p).getId();
    }

    private long newTempChannel(String code, long articleId, String upper, String lower) {
        ChannelCreateRequest c = new ChannelCreateRequest();
        c.setChannelCode(code);
        c.setChannelName("温度通道");
        c.setArticleId(articleId);
        c.setMeasureType(TvacConst.MeasureType.TEMPERATURE);
        c.setUnit("C");
        c.setUpperLimit(new BigDecimal(upper));
        c.setLowerLimit(new BigDecimal(lower));
        return channelService.create(c).getId();
    }

    private void toRunning(long planId) {
        planService.changeStatus(planId, TvacConst.PlanStatus.ISSUED);
        planService.changeStatus(planId, TvacConst.PlanStatus.RUNNING);
    }

    @Test
    void batchCreatePersistsAllArticlesAndRejectsDuplicateCodeWithinBatch() {
        ArticleBatchCreateRequest batch = new ArticleBatchCreateRequest();
        batch.setBatchNo("B-IT-1");
        ArticleBatchCreateRequest.Item i1 = new ArticleBatchCreateRequest.Item();
        i1.setArticleCode("IT-1");
        i1.setArticleName("件一");
        ArticleBatchCreateRequest.Item i2 = new ArticleBatchCreateRequest.Item();
        i2.setArticleCode("IT-1");
        i2.setArticleName("件二");
        batch.setArticles(Arrays.asList(i1, i2));
        assertThrows(BusinessException.class, () -> articleService.createBatch(batch));

        i2.setArticleCode("IT-2");
        List<TvacArticle> saved = articleService.createBatch(batch);
        assertEquals(2, saved.size());
        assertEquals(TvacConst.ArticleStatus.REGISTERED, saved.get(0).getStatus());
        assertEquals("B-IT-1", saved.get(0).getBatchNo());
    }

    @Test
    void duplicateArticleCodeRejected() {
        newArticle("UNIQ-1", "B-U");
        assertThrows(Exception.class, () -> newArticle("UNIQ-1", "B-U2"));
    }

    @Test
    void planStateMachineAdvancesAndSyncsArticleStatus() {
        long articleId = newArticle("SM-1", "B-SM");
        long planId = newPlan("PL-SM-1", articleId);

        assertThrows(BusinessException.class,
                () -> planService.changeStatus(planId, TvacConst.PlanStatus.COMPLETED));
        toRunning(planId);
        assertEquals(TvacConst.ArticleStatus.IN_TEST, articleService.getById(articleId).getStatus());
        assertThrows(BusinessException.class,
                () -> planService.changeStatus(planId, TvacConst.PlanStatus.ISSUED));

        planService.changeStatus(planId, TvacConst.PlanStatus.COMPLETED);
        assertEquals(TvacConst.ArticleStatus.COMPLETED, articleService.getById(articleId).getStatus());
    }

    @Test
    void framesAreLimitJudgedAgainstChannelThresholds() {
        long articleId = newArticle("FR-1", "B-FR");
        long planId = newPlan("PL-FR-1", articleId);
        long channelId = newTempChannel("C-FR-1", articleId, "60", "-40");
        toRunning(planId);

        assertEquals(TvacConst.LimitFlag.NORMAL, createFrame(planId, channelId, "FR-F1", 1, "25.5").getLimitFlag());
        assertEquals(TvacConst.LimitFlag.HIGH, createFrame(planId, channelId, "FR-F2", 1, "60.1").getLimitFlag());
        assertEquals(TvacConst.LimitFlag.LOW, createFrame(planId, channelId, "FR-F3", 1, "-40.1").getLimitFlag());
        // 恰等于边界不算越限
        assertEquals(TvacConst.LimitFlag.NORMAL, createFrame(planId, channelId, "FR-F4", 1, "60").getLimitFlag());
    }

    @Test
    void fullJudgeAcceptPostWorkflowWithAggregationAndDeleteGuards() {
        long articleId = newArticle("E2E-1", "B-E2E");
        long planId = newPlan("PL-E2E-1", articleId);
        long channelId = newTempChannel("C-E2E-1", articleId, "60", "-40");
        toRunning(planId);

        // 循环1/2 各录曲线点，循环3 只在遥测帧出现 -> 实际循环次数应取两者最大
        curvePointService.create(curve(planId, 1, "68.0", "0.001"));
        curvePointService.create(curve(planId, 2, "-62.0", "0.0008"));
        createFrame(planId, channelId, "E2E-F1", 1, "25");
        createFrame(planId, channelId, "E2E-F2", 3, "72");
        createFrame(planId, channelId, "E2E-F3", 2, "-45");

        planService.changeStatus(planId, TvacConst.PlanStatus.COMPLETED);

        // 一计划一报告
        ReportCreateRequest rc = new ReportCreateRequest();
        rc.setReportNo("RPT-E2E-1");
        rc.setPlanId(planId);
        long reportId = reportService.create(rc).getId();
        assertThrows(BusinessException.class, () -> reportService.create(rc));

        // 未判读不能验收
        ReportAcceptRequest earlyAccept = new ReportAcceptRequest();
        earlyAccept.setAcceptedBy("王总");
        assertThrows(BusinessException.class,
                () -> reportService.accept(reportId, earlyAccept));

        ReportJudgeRequest judge = new ReportJudgeRequest();
        judge.setConclusion(TvacConst.Conclusion.QUALIFIED);
        judge.setJudgedBy("张工");
        TvacReport judged = reportService.judge(reportId, judge);
        assertEquals(3, judged.getActualCycles());
        assertEquals(3, judged.getTotalFrames());
        assertEquals(2, judged.getAbnormalFrames());
        assertEquals(0, new BigDecimal("68.0").compareTo(judged.getHighTempReached()));
        assertEquals(0, new BigDecimal("-62.0").compareTo(judged.getLowTempReached()));
        assertEquals(0, new BigDecimal("0.0008").compareTo(judged.getMinPressurePa()));
        assertNotNull(judged.getJudgedTime());

        // 判读后删除仍允许（未验收），然后重建报告
        reportService.delete(reportId);
        long finalReportId = reportService.create(rc).getId();
        reportService.judge(finalReportId, judge);

        ReportAcceptRequest accept = new ReportAcceptRequest();
        accept.setAcceptedBy("王总");
        reportService.accept(finalReportId, accept);
        reportService.post(finalReportId);

        // 已验收/落账：删除拒绝、判读锁定
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.delete(finalReportId));
        assertTrue(ex.getMessage().contains("已验收") || ex.getMessage().contains("已落账"));
        ReportJudgeRequest requalify = new ReportJudgeRequest();
        requalify.setConclusion(TvacConst.Conclusion.UNQUALIFIED);
        requalify.setJudgedBy("李工");
        assertThrows(BusinessException.class,
                () -> reportService.judge(finalReportId, requalify));

        // 已完成计划、已关联计划的试验件均不可直接删除
        assertThrows(BusinessException.class, () -> planService.delete(planId));
        assertThrows(BusinessException.class, () -> articleService.delete(articleId));
    }

    private TvacTmFrame createFrame(long planId, long channelId, String seq, int cycleNo, String value) {
        FrameCreateRequest f = new FrameCreateRequest();
        f.setFrameSeq(seq);
        f.setPlanId(planId);
        f.setChannelId(channelId);
        f.setCycleNo(cycleNo);
        f.setEngValue(new BigDecimal(value));
        return frameService.create(f);
    }

    private CurvePointCreateRequest curve(long planId, int cycle, String temp, String pressure) {
        CurvePointCreateRequest c = new CurvePointCreateRequest();
        c.setPlanId(planId);
        c.setCycleNo(cycle);
        c.setTemperatureC(new BigDecimal(temp));
        c.setPressurePa(new BigDecimal(pressure));
        return c;
    }
}
