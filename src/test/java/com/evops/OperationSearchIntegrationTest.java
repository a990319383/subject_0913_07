package com.evops;

import com.evops.dto.OperationSearchRequest;
import com.evops.entity.TvacTenant;
import com.evops.entity.TvacUser;
import com.evops.security.CurrentUser;
import com.evops.service.OperationQueryService;
import com.evops.service.TenantProvisionService;
import com.evops.vo.OperationSearchItemVo;
import com.evops.vo.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营检索功能集成测试：
 * 多租户/角色数据权限、一对多防放大、≥4 条件 AND/范围组合、
 * pageSize 1-100 兜底、页码与确定性主键游标分页、稳定排序。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OperationSearchIntegrationTest {

    @Autowired
    private OperationQueryService queryService;
    @Autowired
    private TenantProvisionService provisionService;
    @Autowired
    private JdbcTemplate jdbc;

    private final CurrentUser system = CurrentUser.system("bootstrap");

    private Long t1;
    private Long t2;
    private CurrentUser admin1;
    private CurrentUser viewer1;
    private CurrentUser viewer2;
    private CurrentUser admin2;

    private LocalDateTime base = LocalDateTime.of(2026, 1, 1, 8, 0);

    @BeforeEach
    void setUpTenants() {
        TvacTenant tenant1 = provisionService.createTenantAs(system, "T1", "总体一部");
        TvacTenant tenant2 = provisionService.createTenantAs(system, "T2", "总体二部");
        t1 = tenant1.getId();
        t2 = tenant2.getId();        TvacUser uAdmin1 = provisionService.createUserAs(system, "admin1", "p", "管一",
                t1, "TENANT_ADMIN");
        TvacUser uViewer1 = provisionService.createUserAs(system, "viewer1", "p", "看一",
                t1, "TENANT_VIEWER");
        TvacUser uViewer2 = provisionService.createUserAs(system, "viewer2", "p", "看二",
                t1, "TENANT_VIEWER");
        TvacUser uAdmin2 = provisionService.createUserAs(system, "admin2", "p", "管二",
                t2, "TENANT_ADMIN");
        admin1 = CurrentUser.tenantUser("admin1", uAdmin1.getId(), t1, true);
        viewer1 = CurrentUser.tenantUser("viewer1", uViewer1.getId(), t1, false);
        viewer2 = CurrentUser.tenantUser("viewer2", uViewer2.getId(), t1, false);
        admin2 = CurrentUser.tenantUser("admin2", uAdmin2.getId(), t2, true);
    }

    private long article(String code, String name, String batch, Long tenantId,
                         String status, LocalDateTime createTime) {
        jdbc.update("INSERT INTO t_tvac_article "
                        + "(article_code, article_name, target_model, batch_no, tenant_id, status, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                code, name, "M-X", batch, tenantId, status, createTime, createTime);
        return jdbc.queryForObject("SELECT id FROM t_tvac_article WHERE article_code=?",
                Long.class, code);
    }

    private long plan(String code, long articleId, String status, LocalDateTime startTime,
                      LocalDateTime createTime) {
        jdbc.update("INSERT INTO t_tvac_plan (plan_code, plan_name, article_id, high_temp_c, low_temp_c, "
                        + "target_cycles, plan_start_time, status, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                code, "计划" + code, articleId, new BigDecimal("80"), new BigDecimal("-60"),
                3, startTime, status, createTime, createTime);
        return jdbc.queryForObject("SELECT id FROM t_tvac_plan WHERE plan_code=?",
                Long.class, code);
    }

    private void curve(long planId, long articleId, int cycle, LocalDateTime time,
                       String temp, String pressure) {
        jdbc.update("INSERT INTO t_tvac_curve_point (plan_id, article_id, cycle_no, point_time, "
                        + "offset_sec, temperature_c, pressure_pa, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                planId, articleId, cycle, time, cycle * 100,
                new BigDecimal(temp), new BigDecimal(pressure), time, time);
    }

    // ---------- 数据权限 ----------

    @Test
    void tenantAndRoleDataScopeIsEnforcedOnEveryQuery() {
        long a1 = article("OP-A1", "控制计算机", "B-OP-1", t1, "IN_TEST", base);
        long a2 = article("OP-A2", "电源控制器", "B-OP-1", t1, "REGISTERED", base.plusHours(1));
        long a3 = article("OP-A3", "测控单元", "B-OP-2", t2, "REGISTERED", base.plusHours(2));

        assertEquals(3, queryService.searchAs(system, new OperationSearchRequest()).getTotal());
        assertEquals(2, queryService.searchAs(admin1, new OperationSearchRequest()).getTotal());
        assertEquals(1, queryService.searchAs(admin2, new OperationSearchRequest()).getTotal());
        assertEquals(0, queryService.searchAs(viewer2, new OperationSearchRequest()).getTotal());

        // VIEWER 授权 A1 后仅见 A1，且不能看到同租户未授权的 A2
        provisionService.grantArticleAs(system, viewer1.getUserId(), a1);
        PageResult<OperationSearchItemVo> v1 =
                queryService.searchAs(viewer1, new OperationSearchRequest());
        assertEquals(1, v1.getTotal());
        assertEquals(a1, v1.getRecords().get(0).getId());
        // 跨租户对象的授权不影响数据范围（A3 不属于 viewer1 的租户）
        provisionService.grantArticleAs(system, viewer1.getUserId(), a3);
        assertEquals(1, queryService.searchAs(viewer1, new OperationSearchRequest()).getTotal());
    }

    // ---------- 一对多防放大 ----------

    @Test
    void oneToManyRelationsDoNotFanOutMasterRows() {
        long a1 = article("FAN-A1", "被放大试件", "B-FAN", t1, "RUNNING", base);
        long p1 = plan("FAN-P1", a1, "RUNNING", base.plusDays(1), base.plusMinutes(1));
        long p2 = plan("FAN-P2", a1, "COMPLETED", base.plusDays(2), base.plusMinutes(2));
        for (int i = 1; i <= 5; i++) {
            curve(p1, a1, i, base.plusDays(1).plusMinutes(i), "20." + i, "0.001");
            curve(p2, a1, i, base.plusDays(2).plusMinutes(i), "60." + i, "0.0005");
        }

        // 同时叠加计划条件 + 温压曲线条件（命中多个一对多行），主记录仍只出现一次
        OperationSearchRequest q = new OperationSearchRequest();
        q.setArticleId(a1);
        q.setPlanStatus("COMPLETED");
        q.setTempFromC(new BigDecimal("60.0"));
        q.setTempToC(new BigDecimal("61.0"));
        PageResult<OperationSearchItemVo> r = queryService.searchAs(system, q);
        assertEquals(1, r.getTotal());
        assertEquals(1, r.getRecords().size());
        assertEquals(a1, r.getRecords().get(0).getId());
        assertEquals(2L, r.getRecords().get(0).getPlanCount());
        assertEquals(10L, r.getRecords().get(0).getCurvePointCount());

        // 计划主体检索：一个计划多条曲线，计划行同样不放大
        OperationSearchRequest pq = new OperationSearchRequest();
        pq.setObjectType("PLAN");
        pq.setArticleId(a1);
        pq.setTempFromC(new BigDecimal("0"));
        PageResult<OperationSearchItemVo> pr = queryService.searchAs(system, pq);
        assertEquals(2, pr.getTotal());
        Set<Long> planIds = new HashSet<>();
        pr.getRecords().forEach(x -> planIds.add(x.getPlanId()));
        assertEquals(new HashSet<>(Arrays.asList(p1, p2)), planIds);
    }

    // ---------- ≥4 条件 AND/范围组合 ----------

    @Test
    void atLeastFourAndRangeConditionsCompose() {
        long a1 = article("C4-A1", "组合件甲", "B-C4", t1, "COMPLETED", base);
        long a2 = article("C4-A2", "组合件乙", "B-C4", t1, "COMPLETED", base.plusDays(30));
        long a3 = article("C4-A3", "组合件丙", "B-C4", t1, "REGISTERED", base);
        long p1 = plan("C4-P1", a1, "COMPLETED", base.plusDays(10), base.plusMinutes(1));
        long p2 = plan("C4-P2", a2, "RUNNING", base.plusDays(20), base.plusMinutes(2));
        curve(p1, a1, 1, base.plusDays(10), "70.5", "0.0008");
        curve(p2, a2, 1, base.plusDays(20), "25.0", "0.001");

        // 6 个条件 AND：名称模糊 + 批次 + 状态 + 建档日期区间 + 计划状态 + 温度/压力曲线区间
        OperationSearchRequest q = new OperationSearchRequest();
        q.setArticleName("组合件");
        q.setBatchNo("B-C4");
        q.setArticleStatus("COMPLETED");
        q.setCreateTimeFrom(base.minusDays(1));
        q.setCreateTimeTo(base.plusDays(15));
        q.setPlanStatus("COMPLETED");
        q.setTempFromC(new BigDecimal("70.0"));
        q.setTempToC(new BigDecimal("71.0"));
        q.setPressureFromPa(new BigDecimal("0.0001"));
        q.setPressureToPa(new BigDecimal("0.001"));
        PageResult<OperationSearchItemVo> r = queryService.searchAs(system, q);
        assertEquals(1, r.getTotal());
        assertEquals(a1, r.getRecords().get(0).getId());

        // 区间反向直接拒绝
        OperationSearchRequest bad = new OperationSearchRequest();
        bad.setCreateTimeFrom(base.plusDays(2));
        bad.setCreateTimeTo(base);
        assertThrows(IllegalArgumentException.class, () -> queryService.searchAs(system, bad));
    }

    // ---------- 分页：pageSize 兜底 + 稳定排序 + 游标 ----------

    @Test
    void pageSizeBoundedAndStableOrderWithCursorWalk() {
        // 刻意让建档时间大量并列（每秒 3 件），验证主键补齐的确定性次序
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            ids.add(article("PG-A" + i, "分页件" + i, "B-PG", t1,
                    i % 2 == 0 ? "REGISTERED" : "IN_TEST",
                    base.plusSeconds(i / 3)));
        }

        assertThrows(IllegalArgumentException.class,
                () -> queryService.searchAs(system, req(1, 0, null)));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.searchAs(system, req(1, 101, null)));

        // 页码分页：两页拼起来与全集一致，无重复
        PageResult<OperationSearchItemVo> p1 = queryService.searchAs(system, req(1, 20, null));
        PageResult<OperationSearchItemVo> p2 = queryService.searchAs(system, req(2, 20, null));
        assertEquals(23, p1.getTotal());
        assertTrue(p1.isHasNext());
        assertEquals(20, p1.getRecords().size());
        assertEquals(3, p2.getRecords().size());
        assertFalse(p2.isHasNext());
        assertNull(p2.getNextCursor());

        // 游标分页走完全集
        List<Long> cursorWalk = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            PageResult<OperationSearchItemVo> r =
                    queryService.searchAs(system, req(1, 7, cursor));
            r.getRecords().forEach(x -> cursorWalk.add(x.getId()));
            cursor = r.getNextCursor();
            if (cursor == null) {
                break;
            }
        }
        assertEquals(23, cursorWalk.size());
        assertEquals(new HashSet<>(ids), new HashSet<>(cursorWalk));

        // 稳定排序：(create_time DESC, id DESC) 全程严格非增
        assertOrdered(cursorWalk);
        // 页码与游标两种路径顺序一致
        List<Long> offsetWalk = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            queryService.searchAs(system, req(n, 7, null))
                    .getRecords().forEach(x -> offsetWalk.add(x.getId()));
        }
        assertEquals(cursorWalk, offsetWalk);
    }

    private void assertOrdered(List<Long> idOrder) {
        for (int i = 1; i < idOrder.size(); i++) {
            // 直接用 SQL 校验相邻两行满足复合排序键 (create_time DESC, id DESC)
            Integer ok = jdbc.queryForObject(
                    "SELECT CASE WHEN EXISTS (SELECT 1 FROM t_tvac_article x, t_tvac_article y "
                            + "WHERE x.id=? AND y.id=? AND "
                            + "(x.create_time > y.create_time OR "
                            + "(x.create_time = y.create_time AND x.id > y.id))) THEN 1 ELSE 0 END",
                    Integer.class, idOrder.get(i - 1), idOrder.get(i));
            assertEquals(1, ok, "相邻行不满足 create_time DESC, id DESC: "
                    + idOrder.get(i - 1) + " -> " + idOrder.get(i));
        }
    }

    private OperationSearchRequest req(int pageNum, int pageSize, String cursor) {
        OperationSearchRequest q = new OperationSearchRequest();
        q.setBatchNo("B-PG");
        q.setPageNum(pageNum);
        q.setPageSize(pageSize);
        q.setCursor(cursor);
        return q;
    }

    // ---------- 主体切换 + 计划日期范围 ----------

    @Test
    void planSearchWithDateRangeRespectsScopeAndOrder() {
        long a1 = article("DT-A1", "日期件", "B-DT", t1, "IN_TEST", base);
        plan("DT-P1", a1, "ISSUED", base.plusDays(1), base.plusMinutes(1));
        plan("DT-P2", a1, "RUNNING", base.plusDays(5), base.plusMinutes(2));
        plan("DT-P3", a1, "COMPLETED", base.plusDays(12), base.plusMinutes(3));

        OperationSearchRequest q = new OperationSearchRequest();
        q.setObjectType("PLAN");
        // plan_start_time 落在 day+2 ~ day+10 闭区间：仅 DT-P2（day+5）
        q.setPlanTimeFrom(base.plusDays(2));
        q.setPlanTimeTo(base.plusDays(10));
        q.setPageSize(1);
        PageResult<OperationSearchItemVo> r = queryService.searchAs(admin1, q);
        assertEquals(1, r.getTotal());
        assertEquals(1, r.getRecords().size());
        assertEquals("DT-P2", r.getRecords().get(0).getPlanCode());
        assertNotNull(r.getRecords().get(0).getPlanCode());
        assertEquals(0, queryService.searchAs(viewer2, q).getTotal());

        // 非法主体
        OperationSearchRequest bad = new OperationSearchRequest();
        bad.setObjectType("REPORT");
        assertThrows(IllegalArgumentException.class, () -> queryService.searchAs(system, bad));
    }
}
