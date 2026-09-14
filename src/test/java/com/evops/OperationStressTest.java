package com.evops;

import com.evops.dto.OperationSearchRequest;
import com.evops.dto.TmStatsQuery;
import com.evops.entity.TvacTenant;
import com.evops.security.CurrentUser;
import com.evops.service.OperationQueryService;
import com.evops.service.TenantProvisionService;
import com.evops.vo.ArticleFrameStatsVo;
import com.evops.vo.OperationSearchItemVo;
import com.evops.vo.PageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 困难级压测（独立 stress 内存库，全量数据只加载一次，不回滚）：
 * 1) 100,000 条试验件 + 20,000 个计划 + 100,000 条温压曲线事件上的
 *    ≥4 条件 AND/范围组合、一对多防放大、稳定排序与确定性主键游标分页；
 * 2) 300,000 条遥测帧、1,000 个试验循环上按试验件分区聚合，
 *    通道维度过滤走 EXISTS 半连接，帧数禁止被通道关联放大。
 */
@SpringBootTest
@ActiveProfiles("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationStressTest {

    private static final int ARTICLES = 100_000;
    private static final int PLANNED_ARTICLES = 10_000;
    private static final int FRAMED_ARTICLES = 100;
    private static final int FRAMES = 300_000;
    private static final int CYCLES = 1_000;
    private static final int CHANNELS = 4;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OperationQueryService queryService;
    @Autowired
    private TenantProvisionService provisionService;

    private final CurrentUser system = CurrentUser.system("bootstrap");
    private CurrentUser adminTenant0;

    private final LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0);
    private final List<Long> tenantIds = new ArrayList<>();

    @BeforeAll
    void load() {
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM t_tvac_article", Long.class);
        if (existing != null && existing >= ARTICLES) {
            return;
        }
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            TvacTenant t = provisionService.createTenantAs(system, "ST" + i, "压测租户" + i);
            tenantIds.add(t.getId());
        }
        Long tenant0 = tenantIds.get(0);
        adminTenant0 = CurrentUser.tenantUser("stress-admin", -1L, tenant0, true);

        insertArticles();
        long[] planMinIds = insertPlans();
        insertCurves(planMinIds);
        insertChannelsAndFrames(planMinIds);
        System.out.println("[stress] data load took "
                + (System.currentTimeMillis() - t0) / 1000 + "s");
    }

    private void insertArticles() {
        String[] statuses = {"REGISTERED", "IN_TEST", "COMPLETED", "SCRAPPED"};
        List<Object[]> batch = new ArrayList<>(10_000);
        for (int i = 0; i < ARTICLES; i++) {
            Timestamp ts = Timestamp.valueOf(base.plusSeconds(i / 50L));
            batch.add(new Object[]{"S-" + i, "压测件" + i, "M-" + (i % 5),
                    "BATCH-" + (i % 50), tenantIds.get(i % 3), statuses[i % 4], ts, ts});
            if (batch.size() == 10_000) {
                jdbc.batchUpdate("INSERT INTO t_tvac_article "
                        + "(article_code, article_name, target_model, batch_no, tenant_id, status, "
                        + "create_time, update_time) VALUES (?,?,?,?,?,?,?,?)", batch);
                batch.clear();
            }
        }
        flush(batch, "INSERT INTO t_tvac_article "
                + "(article_code, article_name, target_model, batch_no, tenant_id, status, "
                + "create_time, update_time) VALUES (?,?,?,?,?,?,?,?)");
    }

    /** @return 每件（下标=件序号-1）两个计划中的较小ID（RUNNING 计划先插入） */
    private long[] insertPlans() {
        String[] planStatus = {"RUNNING", "COMPLETED"};
        List<Object[]> batch = new ArrayList<>(10_000);
        for (int i = 0; i < PLANNED_ARTICLES; i++) {
            for (int k = 0; k < 2; k++) {
                Timestamp ts = Timestamp.valueOf(base.plusDays(1).plusMinutes(i * 2L + k));
                batch.add(new Object[]{"SP-" + i + "-" + k, "p", (long) (i + 1),
                        new BigDecimal("80"), new BigDecimal("-60"), 3, ts, planStatus[k], ts, ts});
            }
            if (batch.size() == 10_000) {
                jdbc.batchUpdate("INSERT INTO t_tvac_plan "
                        + "(plan_code, plan_name, article_id, high_temp_c, low_temp_c, "
                        + "target_cycles, plan_start_time, status, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)", batch);
                batch.clear();
            }
        }
        flush(batch, "INSERT INTO t_tvac_plan "
                + "(plan_code, plan_name, article_id, high_temp_c, low_temp_c, "
                + "target_cycles, plan_start_time, status, create_time, update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)");

        long[] minIds = new long[PLANNED_ARTICLES];
        List<long[]> rows = jdbc.query(
                "SELECT article_id, MIN(id) FROM t_tvac_plan WHERE plan_code LIKE 'SP-%' "
                        + "GROUP BY article_id ORDER BY article_id",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
        for (long[] r : rows) {
            minIds[(int) r[0] - 1] = r[1];
        }
        return minIds;
    }

    /** 每件 10 个曲线点：偶数序号=高温点(72.50)，奇数序号=低温点(-55.25)，偶数序号属 RUNNING 计划。 */
    private void insertCurves(long[] planMinIds) {
        List<Object[]> batch = new ArrayList<>(10_000);
        for (int i = 0; i < PLANNED_ARTICLES; i++) {
            long runPlan = planMinIds[i];
            long donePlan = runPlan + 1;
            for (int k = 0; k < 10; k++) {
                long pid = (k % 2 == 0) ? runPlan : donePlan;
                int cycle = k + 1;
                String temp = k % 2 == 0 ? "72.50" : "-55.25";
                String pressure = k < 5 ? "0.00080" : "0.00020";
                Timestamp ts = Timestamp.valueOf(base.plusDays(2).plusMinutes(i * 10L + k));
                batch.add(new Object[]{pid, (long) (i + 1), cycle, ts, cycle * 60,
                        new BigDecimal(temp), new BigDecimal(pressure), ts, ts});
            }
            if (batch.size() == 10_000) {
                jdbc.batchUpdate("INSERT INTO t_tvac_curve_point "
                        + "(plan_id, article_id, cycle_no, point_time, offset_sec, "
                        + "temperature_c, pressure_pa, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", batch);
                batch.clear();
            }
        }
        flush(batch, "INSERT INTO t_tvac_curve_point "
                + "(plan_id, article_id, cycle_no, point_time, offset_sec, "
                + "temperature_c, pressure_pa, create_time, update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?)");
    }

    /**
     * 前 100 件每件 4 通道（400 行），帧 3,000/件共 300,000：
     * 通道按 j%4 轮转（0=TEMPERATURE），cycle = global%1000+1，每 10 帧 1 帧 HIGH。
     */
    private void insertChannelsAndFrames(long[] planMinIds) {
        String[] types = {"TEMPERATURE", "PRESSURE", "VOLTAGE", "CURRENT"};
        List<Object[]> channels = new ArrayList<>(10_000);
        for (int i = 1; i <= FRAMED_ARTICLES; i++) {
            for (int k = 0; k < CHANNELS; k++) {
                channels.add(new Object[]{"SC-" + i + "-" + k, "通道" + k, (long) i,
                        types[k], "U", new BigDecimal("80"), new BigDecimal("-60"),
                        "ENABLED", Timestamp.valueOf(base), Timestamp.valueOf(base)});
            }
        }
        flush(channels, "INSERT INTO t_tvac_channel "
                + "(channel_code, channel_name, article_id, measure_type, unit, "
                + "upper_limit, lower_limit, status, create_time, update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)");

        // 通道为本库首批通道数据，ID 从 1 连续
        List<long[]> chRows = jdbc.query(
                "SELECT id, article_id FROM t_tvac_channel WHERE channel_code LIKE 'SC-%' "
                        + "ORDER BY id",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
        long[][] channelByArticle = new long[FRAMED_ARTICLES][CHANNELS];
        for (long[] r : chRows) {
            int articleIdx = (int) r[1] - 1;
            for (int k = 0; k < CHANNELS; k++) {
                if (channelByArticle[articleIdx][k] == 0) {
                    channelByArticle[articleIdx][k] = r[0];
                    break;
                }
            }
        }

        List<Object[]> frames = new ArrayList<>(10_000);
        int framesPerArticle = FRAMES / FRAMED_ARTICLES;
        int global = 0;
        for (int i = 1; i <= FRAMED_ARTICLES; i++) {
            for (int j = 0; j < framesPerArticle; j++, global++) {
                long channelId = channelByArticle[i - 1][j % CHANNELS];
                int cycle = (global % CYCLES) + 1;
                String flag = j % 10 == 0 ? "HIGH" : "NORMAL";
                Timestamp ts = Timestamp.valueOf(base.plusSeconds(global));
                frames.add(new Object[]{"SF-" + global, planMinIds[i - 1], channelId, (long) i,
                        cycle, ts, new BigDecimal((j % 201) - 100), flag, ts, ts});
                if (frames.size() == 10_000) {
                    jdbc.batchUpdate("INSERT INTO t_tvac_tm_frame "
                            + "(frame_seq, plan_id, channel_id, article_id, cycle_no, frame_time, "
                            + "eng_value, limit_flag, create_time, update_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)", frames);
                    frames.clear();
                }
            }
        }
        flush(frames, "INSERT INTO t_tvac_tm_frame "
                + "(frame_seq, plan_id, channel_id, article_id, cycle_no, frame_time, "
                + "eng_value, limit_flag, create_time, update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)");
    }

    private void flush(List<Object[]> batch, String sql) {
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(sql, batch);
            batch.clear();
        }
    }

    // ================= 10 万对象/事件检索 =================

    @Test
    void searchTotalsAndTenantScopeOnHundredThousandRows() {
        assertEquals(100_000L, queryService.searchAs(system, new OperationSearchRequest()).getTotal());
        // 租户0：article index i % 3 == 0
        assertEquals(33_334L,
                queryService.searchAs(adminTenant0, new OperationSearchRequest()).getTotal());
    }

    @Test
    void compoundAndRangeFilterWithFanOutGuard() {
        // 6 条件 AND：租户0(i%3=0) + REGISTERED(i%4=0) + BATCH-0(i%50=0)
        // + 建档日期区间 + RUNNING 计划存在 + 高温曲线点(72.50, 压力 0.0008) 存在
        // 数学解：i 是 300 的倍数且 i<10000 -> 0,300,...,9900 共 34 件
        OperationSearchRequest q = new OperationSearchRequest();
        q.setArticleStatus("REGISTERED");
        q.setBatchNo("BATCH-0");
        q.setCreateTimeFrom(base);
        q.setCreateTimeTo(base.plusDays(1));
        q.setPlanStatus("RUNNING");
        q.setTempFromC(new BigDecimal("72.0"));
        q.setTempToC(new BigDecimal("73.0"));
        q.setPressureFromPa(new BigDecimal("0.0001"));
        q.setPressureToPa(new BigDecimal("0.0009"));
        q.setPageSize(100);

        PageResult<OperationSearchItemVo> r = queryService.searchAs(adminTenant0, q);
        assertEquals(34, r.getTotal());
        assertEquals(34, r.getRecords().size());
        Set<Long> uniqueIds = new HashSet<>();
        r.getRecords().forEach(x -> {
            uniqueIds.add(x.getId());
            // 一件两计划、十曲线点：标量子查询计数，主行绝未被一对多放大
            assertEquals(2L, x.getPlanCount());
            assertEquals(10L, x.getCurvePointCount());
        });
        assertEquals(34, uniqueIds.size(), "一对多关联放大了主记录");
    }

    @Test
    void stableOrderAndCursorPaginationAcrossLargeResultSet() {
        assertThrows(IllegalArgumentException.class, () -> {
            OperationSearchRequest bad = new OperationSearchRequest();
            bad.setPageSize(101);
            queryService.searchAs(adminTenant0, bad);
        });

        // 以租户0全集（33,334 行，create_time 每 50 件并列一次）验证稳定排序与游标翻页
        String cursor = null;
        List<Long> seen = new ArrayList<>();
        long lastTime = Long.MAX_VALUE;
        long lastId = Long.MAX_VALUE;
        for (int page = 0; page < 5; page++) {
            OperationSearchRequest q = new OperationSearchRequest();
            q.setPageSize(100);
            q.setCursor(cursor);
            PageResult<OperationSearchItemVo> r = queryService.searchAs(adminTenant0, q);
            assertEquals(100, r.getRecords().size());
            for (OperationSearchItemVo item : r.getRecords()) {
                long t = item.getCreateTime()
                        .toEpochSecond(java.time.ZoneOffset.UTC);
                assertTrue(t < lastTime || (t == lastTime && item.getId() < lastId),
                        "排序不稳定: " + t + "/" + item.getId());
                assertTrue(seen.add(item.getId()), "游标翻页出现重复主记录");
                lastTime = t;
                lastId = item.getId();
            }
            cursor = r.getNextCursor();
        }

        // 游标与页码路径同页结果一致（首页）
        OperationSearchRequest c0 = new OperationSearchRequest();
        c0.setPageSize(100);
        OperationSearchRequest p0 = new OperationSearchRequest();
        p0.setPageNum(1);
        p0.setPageSize(100);
        List<Long> cursorFirst = ids(queryService.searchAs(adminTenant0, c0));
        List<Long> offsetFirst = ids(queryService.searchAs(adminTenant0, p0));
        assertEquals(offsetFirst, cursorFirst);
    }

    private List<Long> ids(PageResult<OperationSearchItemVo> r) {
        List<Long> ids = new ArrayList<>();
        r.getRecords().forEach(x -> ids.add(x.getId()));
        return ids;
    }

    // ================= 30 万帧 / 1000 循环分区聚合 =================

    @Test
    void frameAggregationPartitionsByArticleWithoutChannelFanOut() {
        TmStatsQuery q = new TmStatsQuery();
        q.setPageSize(100);
        PageResult<ArticleFrameStatsVo> all = queryService.frameStatsAs(system, q);
        assertEquals(FRAMED_ARTICLES, all.getTotal());
        assertEquals(FRAMED_ARTICLES, all.getRecords().size());
        long totalFrames = 0;
        long prevArticleId = -1;
        for (ArticleFrameStatsVo s : all.getRecords()) {
            assertEquals(3_000L, s.getTotalFrames(), "article " + s.getArticleId());
            assertEquals(300L, s.getAbnormalFrames());
            assertEquals(4L, s.getChannelCount());
            assertEquals(1_000, s.getMaxCycleNo());
            assertTrue(s.getArticleId() > prevArticleId, "聚合结果未按分区键稳定排序");
            prevArticleId = s.getArticleId();
            totalFrames += s.getTotalFrames();
        }
        assertEquals(FRAMES, totalFrames, "帧数必须与帧表行数一致，禁止通道关联放大");

        // 通道测量类型半连接过滤：TEMPERATURE = j%4==0 -> 750 帧/件，不放大
        TmStatsQuery temp = new TmStatsQuery();
        temp.setMeasureType("TEMPERATURE");
        temp.setPageSize(100);
        PageResult<ArticleFrameStatsVo> tempStats = queryService.frameStatsAs(system, temp);
        long tempFrames = 0;
        for (ArticleFrameStatsVo s : tempStats.getRecords()) {
            assertEquals(750L, s.getTotalFrames());
            tempFrames += s.getTotalFrames();
        }
        assertEquals(75_000L, tempFrames);

        // 与直接在帧表上按通道半连接计数的结果交叉核对（放大检测的独立口径）
        Long direct = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_tm_frame f WHERE EXISTS ("
                        + "SELECT 1 FROM t_tvac_channel ch WHERE ch.id=f.channel_id "
                        + "AND ch.measure_type='TEMPERATURE')", Long.class);
        assertEquals(direct, tempFrames);

        // 循环范围 + 越限标记组合：cycle 501..1000 区间内 HIGH 帧。
        // j=0..2999 三次穿过 1000 循环，每千帧区间 [500,999] 内 j%10==0 有 50 个 -> 每件 150
        TmStatsQuery range = new TmStatsQuery();
        range.setCycleFrom(501);
        range.setCycleTo(1_000);
        range.setLimitFlag("HIGH");
        range.setPageSize(100);
        PageResult<ArticleFrameStatsVo> rangeStats = queryService.frameStatsAs(system, range);
        rangeStats.getRecords().forEach(s -> assertEquals(150L, s.getTotalFrames()));

        // 租户数据权限：有帧的 100 件中 index%3==0 的属于租户0
        TmStatsQuery scoped = new TmStatsQuery();
        scoped.setPageSize(100);
        PageResult<ArticleFrameStatsVo> byTenant = queryService.frameStatsAs(adminTenant0, scoped);
        long expectedGroups = java.util.stream.IntStream.range(0, FRAMED_ARTICLES)
                .filter(i -> i % 3 == 0).count();
        assertEquals(expectedGroups, byTenant.getTotal());
        byTenant.getRecords().forEach(s -> assertEquals(3_000L, s.getTotalFrames()));
    }
}
