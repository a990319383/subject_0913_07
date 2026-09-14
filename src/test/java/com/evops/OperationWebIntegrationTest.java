package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营检索 HTTP 端到端：Shiro HTTP Basic 认证、多租户账号数据范围、
 * pageSize 边界与非法游标在真实请求线程上的表现。
 * 使用独立 stress 之外的 webtest 内存库，服务端口随机。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("webtest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationWebIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final LocalDateTime base = LocalDateTime.of(2026, 3, 1, 8, 0);
    private long tenant1;
    private long viewerId;

    @BeforeAll
    void seed() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM t_tvac_tenant", Long.class) > 0) {
            return;
        }
        jdbc.update("INSERT INTO t_tvac_tenant (id, tenant_code, tenant_name, status, create_time, update_time) "
                + "VALUES (101,'WT1','网络租户一','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "(102,'WT2','网络租户二','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO t_tvac_user (id, username, password, real_name, tenant_id, role, status, create_time, update_time) "
                + "VALUES (201,'wadmin','p','网管',101,'TENANT_ADMIN','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "(202,'wviewer','p','网看',101,'TENANT_VIEWER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        tenant1 = 101L;
        viewerId = 202L;
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO t_tvac_article "
                            + "(article_code, article_name, batch_no, tenant_id, status, create_time, update_time) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    "W-A" + i, "网试件" + i, "WB", 101L, "REGISTERED",
                    java.sql.Timestamp.valueOf(base.plusHours(i)),
                    java.sql.Timestamp.valueOf(base.plusHours(i)));
        }
        jdbc.update("INSERT INTO t_tvac_article "
                        + "(article_code, article_name, batch_no, tenant_id, status, create_time, update_time) "
                        + "VALUES ('W-B1','他租户件','WB',102,'REGISTERED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // wviewer 仅被授权 W-A1
        jdbc.update("INSERT INTO t_tvac_user_grant (user_id, object_type, article_id) "
                + "SELECT 202,'ARTICLE',id FROM t_tvac_article WHERE article_code='W-A1'");
    }

    private HttpHeaders auth(String user, String pwd) {
        HttpHeaders h = new HttpHeaders();
        if (user != null) {
            String token = Base64.getEncoder().encodeToString(
                    (user + ":" + pwd).getBytes(StandardCharsets.UTF_8));
            h.set("Authorization", "Basic " + token);
        }
        h.set("Content-Type", "application/json");
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<String> resp = rest.exchange(url("/api/tvac/operation/search"),
                HttpMethod.POST,
                new HttpEntity<>("{}", auth(null, null)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void dataScopeDiffersByAccountOverHttp() throws Exception {
        String body = "{}";

        JsonNode system = post(body, "bootstrap", "bootstrap");
        assertTrue(system.path("success").asBoolean());
        assertEquals(4, system.path("data").path("total").asInt());

        JsonNode admin = post(body, "wadmin", "p");
        assertEquals(3, admin.path("data").path("total").asInt());

        JsonNode viewer = post(body, "wviewer", "p");
        assertEquals(1, viewer.path("data").path("total").asInt());
        assertEquals("W-A1", viewer.path("data").path("records").get(0).path("code").asText());

        // 错误口令认证失败
        ResponseEntity<String> bad = rest.exchange(url("/api/tvac/operation/search"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth("wviewer", "WRONG")), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, bad.getStatusCode());
    }

    @Test
    void compoundFiltersAndPaginationEnvelopeWorkOverHttp() throws Exception {
        // 4+ 条件 AND：批次 + 状态 + 名称模糊 + 建档日期区间
        String q = "{\"batchNo\":\"WB\",\"articleStatus\":\"REGISTERED\","
                + "\"articleName\":\"网\",\"createTimeFrom\":\"2026-03-01T09:00:00\","
                + "\"createTimeTo\":\"2026-03-01T11:00:00\",\"pageSize\":2}";
        JsonNode r = post(q, "bootstrap", "bootstrap");
        assertTrue(r.path("success").asBoolean());
        // 闭区间 09:00~11:00 命中 W-A1/W-A2/W-A3 共 3 件，首页 2 条
        assertEquals(3, r.path("data").path("total").asInt());
        assertEquals(2, r.path("data").path("records").size());
        assertTrue(r.path("data").path("hasNext").asBoolean());
        String cursor = r.path("data").path("nextCursor").asText();
        assertFalse(cursor.isEmpty());

        // 游标取下一页（keyset 翻页必须携带与首页相同的过滤条件）：剩余 1 条，total 不变
        JsonNode page2 = post("{\"batchNo\":\"WB\",\"articleStatus\":\"REGISTERED\","
                + "\"articleName\":\"网\",\"createTimeFrom\":\"2026-03-01T09:00:00\","
                + "\"createTimeTo\":\"2026-03-01T11:00:00\",\"pageSize\":2,\"cursor\":\""
                + cursor + "\"}", "bootstrap", "bootstrap");
        assertEquals(3, page2.path("data").path("total").asInt());
        assertEquals(1, page2.path("data").path("records").size());
        assertFalse(page2.path("data").path("hasNext").asBoolean());
    }

    @Test
    void pageSizeBoundariesAndBadCursorAreRejected() throws Exception {
        JsonNode tooBig = post("{\"pageSize\":101}", "bootstrap", "bootstrap");
        assertFalse(tooBig.path("success").asBoolean());
        assertTrue(tooBig.path("message").asText().contains("pageSize"));

        JsonNode zero = post("{\"pageSize\":0}", "bootstrap", "bootstrap");
        assertFalse(zero.path("success").asBoolean());

        JsonNode badCursor = post("{\"cursor\":\"not-a-cursor\"}", "bootstrap", "bootstrap");
        assertFalse(badCursor.path("success").asBoolean());
    }

    @Test
    void tmStatsEndpointEnforcesScopeAndAggregates() throws Exception {
        // 给 W-A1 挂计划/通道/帧（帧不经通道 JOIN 放大）
        Long articleId = jdbc.queryForObject(
                "SELECT id FROM t_tvac_article WHERE article_code='W-A1'", Long.class);
        jdbc.update("INSERT INTO t_tvac_plan (plan_code, plan_name, article_id, high_temp_c, low_temp_c, "
                + "target_cycles, status, create_time, update_time) "
                + "VALUES ('W-P1','wp',?,80,-60,2,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", articleId);
        jdbc.update("INSERT INTO t_tvac_channel (channel_code, channel_name, article_id, measure_type, "
                + "upper_limit, lower_limit, status, create_time, update_time) "
                + "VALUES ('W-C1','温',?,'TEMPERATURE',80,-60,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", articleId);
        jdbc.update("INSERT INTO t_tvac_tm_frame "
                + "(frame_seq, plan_id, channel_id, article_id, cycle_no, frame_time, eng_value, limit_flag, create_time, update_time) "
                + "SELECT 'WF-'||x, (SELECT id FROM t_tvac_plan WHERE plan_code='W-P1'), "
                + "(SELECT id FROM t_tvac_channel WHERE channel_code='W-C1'), id, 1, CURRENT_TIMESTAMP, "
                + "CASE WHEN x % 10 = 0 THEN 90 ELSE 25 END, "
                + "CASE WHEN x % 10 = 0 THEN 'HIGH' ELSE 'NORMAL' END, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "FROM t_tvac_article, SYSTEM_RANGE(1, 50) WHERE article_code='W-A1'");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/tvac/operation/tm-stats?pageSize=100"),
                HttpMethod.GET, new HttpEntity<>(auth("wviewer", "p")), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode r = json.readTree(resp.getBody());
        assertTrue(r.path("success").asBoolean());
        assertEquals(1, r.path("data").path("total").asInt());
        JsonNode stat = r.path("data").path("records").get(0);
        assertEquals(50, stat.path("totalFrames").asInt());
        assertEquals(5, stat.path("abnormalFrames").asInt());
        assertEquals("W-A1", stat.path("articleCode").asText());
    }

    private JsonNode post(String body, String user, String pwd) throws Exception {
        ResponseEntity<String> resp = rest.exchange(url("/api/tvac/operation/search"),
                HttpMethod.POST, new HttpEntity<>(body, auth(user, pwd)), String.class);
        return json.readTree(resp.getBody());
    }
}
