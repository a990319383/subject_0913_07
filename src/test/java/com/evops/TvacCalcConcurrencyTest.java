package com.evops;

import com.evops.constant.TvacConst;
import com.evops.dto.ArticleCreateRequest;
import com.evops.dto.CalcRunCreateRequest;
import com.evops.dto.RuleBandRequest;
import com.evops.dto.RuleSetCreateRequest;
import com.evops.dto.RuleVersionCreateRequest;
import com.evops.entity.TvacObservation;
import com.evops.mapper.TvacObservationMapper;
import com.evops.service.TvacArticleService;
import com.evops.service.TvacCalcService;
import com.evops.service.TvacRuleService;
import com.evops.vo.CalcRunDetailVo;
import com.evops.vo.RuleVersionVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间计算并发重算（tvac-4 困难级约束）：
 * 多线程同时对同一（规则版本 + UTC 窗口）发起计算，
 * 数据库唯一约束只放行一个批次，其余请求改读已落库批次——
 * 全库只存在一个批次、一套明细，绝不产生两份结果。
 *
 * <p>独立 calctest 内存库，真实提交、不回滚。
 */
@SpringBootTest
@ActiveProfiles("calctest")
class TvacCalcConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private TvacCalcService calcService;
    @Autowired
    private TvacRuleService ruleService;
    @Autowired
    private TvacArticleService articleService;
    @Autowired
    private TvacObservationMapper observationMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRecalcProducesExactlyOneRun() throws Exception {
        // 夹具：对象 + 规则集（UTC）+ 启用版本（峰/平/谷三段）+ 窗口内遥测
        ArticleCreateRequest a = new ArticleCreateRequest();
        a.setArticleCode("CC-ART-1");
        a.setArticleName("并发件");
        a.setBatchNo("B-CC");
        long articleId = articleService.create(a).getId();

        RuleSetCreateRequest s = new RuleSetCreateRequest();
        s.setSetCode("CC-SET-1");
        s.setSetName("并发规则集");
        s.setArticleId(articleId);
        s.setMissionTz("UTC");
        long setId = ruleService.createSet(s).getId();

        RuleVersionCreateRequest v = new RuleVersionCreateRequest();
        v.setBands(Arrays.asList(
                band(TvacConst.BandType.PEAK, 480, 1020),
                band(TvacConst.BandType.FLAT, 1020, 1260),
                band(TvacConst.BandType.VALLEY, 1260, 480)));
        RuleVersionVo version = ruleService.createVersion(setId, v);
        ruleService.enable(version.getVersion().getId());

        LocalDateTime ws = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime we = LocalDateTime.of(2026, 6, 2, 0, 0);
        for (int i = 0; i < 24; i++) {
            TvacObservation o = new TvacObservation();
            o.setRecordType(TvacConst.RecordType.FRAME);
            o.setBizKey("CC-OB-" + i);
            o.setArticleId(articleId);
            o.setFrameSeq("CC-FS-" + i);
            o.setObserveTime(ws.plusHours(i));
            o.setEngValue(new BigDecimal("1.5").add(new BigDecimal(i)));
            o.setStatus("IMPORTED");
            observationMapper.insert(o);
        }

        CalcRunCreateRequest req = new CalcRunCreateRequest();
        req.setSetId(setId);
        req.setWindowStartUtc(ws);
        req.setWindowEndUtc(we);

        // 8 线程同一起跑线并发重算
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CalcRunDetailVo>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return calcService.execute(req);
            }));
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        Set<Long> runIds = new HashSet<>();
        for (Future<CalcRunDetailVo> f : futures) {
            runIds.add(f.get(60, TimeUnit.SECONDS).getRun().getId());
        }
        pool.shutdownNow();

        // 全部请求收敛到同一批次；库中有且仅有一个批次、一套明细
        assertEquals(1, runIds.size(), "并发重算产生了多份结果: " + runIds);
        Long runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_calc_run WHERE version_id=? "
                        + "AND window_start_utc=? AND window_end_utc=?",
                Long.class, version.getVersion().getId(),
                Timestamp.valueOf(ws), Timestamp.valueOf(we));
        assertEquals(1L, runCount);
        Long detailCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_calc_detail d JOIN t_tvac_calc_run r ON d.run_id=r.id "
                        + "WHERE r.version_id=? AND r.window_start_utc=? AND r.window_end_utc=?",
                Long.class, version.getVersion().getId(),
                Timestamp.valueOf(ws), Timestamp.valueOf(we));
        assertEquals(3L, detailCount);
    }

    private static RuleBandRequest band(String type, int startMin, int endMin) {
        RuleBandRequest b = new RuleBandRequest();
        b.setBandType(type);
        b.setStartMin(startMin);
        b.setEndMin(endMin);
        return b;
    }
}
