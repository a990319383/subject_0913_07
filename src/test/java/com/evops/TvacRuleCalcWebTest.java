package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时序判读规则与区间计算 HTTP 端到端（tvac-4）：
 * 规则集/版本/启用/冻结、重叠拒绝、区间计算幂等、任务时区展示，
 * 以及未认证拒绝。独立 calcwebtest 内存库，业务编号 WR-* 隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("calcwebtest")
class TvacRuleCalcWebTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

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

    private JsonNode post(String path, String body) throws Exception {
        ResponseEntity<String> resp = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth("bootstrap", "bootstrap")), String.class);
        return json.readTree(resp.getBody());
    }

    private JsonNode put(String path, String body) throws Exception {
        ResponseEntity<String> resp = rest.exchange(url(path), HttpMethod.PUT,
                new HttpEntity<>(body, auth("bootstrap", "bootstrap")), String.class);
        return json.readTree(resp.getBody());
    }

    @Test
    void ruleCalcFlowOverHttp() throws Exception {
        // 未认证拒绝
        ResponseEntity<String> anon = rest.exchange(url("/api/tvac/rule-sets"),
                HttpMethod.POST, new HttpEntity<>("{}", auth(null, null)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anon.getStatusCode());

        // 夹具：试验件 + 窗口内遥测观测（设备 UTC）
        jdbc.update("INSERT INTO t_tvac_article "
                        + "(article_code, article_name, batch_no, status, create_time, update_time) "
                        + "VALUES ('WR-A1','网规件','WRB','REGISTERED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        Long articleId = jdbc.queryForObject(
                "SELECT id FROM t_tvac_article WHERE article_code='WR-A1'", Long.class);
        String[][] rows = {
                {"WR-OB-1", "2026-03-01 02:00:00", "10.0001"},   // 10:00 +8 峰值
                {"WR-OB-2", "2026-03-01 07:59:00", "10.0002"},   // 15:59 峰值
                {"WR-OB-3", "2026-03-01 08:00:00", "20.0000"},   // 16:00 平段
                {"WR-OB-4", "2026-03-01 16:30:00", "3.5000"}};   // 次日00:30 谷值（跨日）
        for (String[] r : rows) {
            jdbc.update("INSERT INTO t_tvac_observation "
                            + "(record_type, biz_key, article_id, observe_time, eng_value, status, "
                            + "create_time, update_time) VALUES ('FRAME',?,?,?,?,'IMPORTED',"
                            + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    r[0], articleId, java.sql.Timestamp.valueOf(r[1]), new java.math.BigDecimal(r[2]));
        }

        // 建档规则集（任务时区 +8）
        JsonNode set = post("/api/tvac/rule-sets",
                "{\"setCode\":\"WR-SET-1\",\"setName\":\"网规集\",\"articleId\":" + articleId
                        + ",\"missionTz\":\"Asia/Shanghai\"}");
        assertTrue(set.path("success").asBoolean(), set.toString());
        long setId = set.path("data").path("id").asLong();

        // 区间重叠必须拒绝（跨日 VALLEY 与凌晨 PEAK 相交）
        JsonNode overlap = post("/api/tvac/rule-sets/" + setId + "/versions",
                "{\"bands\":[{\"bandType\":\"VALLEY\",\"startMin\":1320,\"endMin\":600},"
                        + "{\"bandType\":\"PEAK\",\"startMin\":500,\"endMin\":700}]}");
        assertFalse(overlap.path("success").asBoolean());
        assertTrue(overlap.path("message").asText().contains("重叠"));

        // 峰/平/谷三段，谷值跨日
        JsonNode v1 = post("/api/tvac/rule-sets/" + setId + "/versions",
                "{\"bands\":[{\"bandType\":\"PEAK\",\"startMin\":600,\"endMin\":960},"
                        + "{\"bandType\":\"FLAT\",\"startMin\":960,\"endMin\":1320},"
                        + "{\"bandType\":\"VALLEY\",\"startMin\":1320,\"endMin\":600}]}");
        assertTrue(v1.path("success").asBoolean(), v1.toString());
        long versionId = v1.path("data").path("version").path("id").asLong();
        assertEquals(1, v1.path("data").path("version").path("versionNo").asInt());

        // 启用；启用后区间冻结
        assertTrue(put("/api/tvac/rule-versions/" + versionId + "/enable", "{}")
                .path("success").asBoolean());
        JsonNode frozen = put("/api/tvac/rule-versions/" + versionId + "/bands",
                "{\"bands\":[{\"bandType\":\"PEAK\",\"startMin\":0,\"endMin\":600}]}");
        assertFalse(frozen.path("success").asBoolean());

        // 执行区间计算：UTC 窗口 [03-01, 03-02)，按任务时区展示为 [08:00, 次日08:00)
        String calcBody = "{\"setId\":" + setId
                + ",\"windowStartUtc\":\"2026-03-01T00:00:00\","
                + "\"windowEndUtc\":\"2026-03-02T00:00:00\"}";
        JsonNode run = post("/api/tvac/calc-runs", calcBody);
        assertTrue(run.path("success").asBoolean(), run.toString());
        JsonNode data = run.path("data");
        assertEquals(1, data.path("run").path("versionNo").asInt());
        assertEquals(4, data.path("run").path("obsCount").asInt());
        assertEquals("2026-03-01T08:00:00", data.path("windowStartMission").asText());
        assertEquals("2026-03-02T08:00:00", data.path("windowEndMission").asText());
        assertEquals(3, data.path("details").size());
        long runId = data.path("run").path("id").asLong();
        for (JsonNode d : data.path("details")) {
            if ("PEAK".equals(d.path("bandType").asText())) {
                assertEquals(2, d.path("obsCount").asInt());
                assertEquals(0, d.path("avgValue").decimalValue()
                        .compareTo(new java.math.BigDecimal("10.0002")));
            }
            if ("VALLEY".equals(d.path("bandType").asText())) {
                assertEquals(1, d.path("obsCount").asInt());
            }
        }

        // 重复提交幂等：同范围返回同一批次
        JsonNode again = post("/api/tvac/calc-runs", calcBody);
        assertEquals(runId, again.path("data").path("run").path("id").asLong());

        // 批次详情可读；规则集列表可查
        ResponseEntity<String> detail = rest.exchange(url("/api/tvac/calc-runs/" + runId),
                HttpMethod.GET, new HttpEntity<>(auth("bootstrap", "bootstrap")), String.class);
        JsonNode detailJson = json.readTree(detail.getBody());
        assertTrue(detailJson.path("success").asBoolean());
        assertEquals(3, detailJson.path("data").path("details").size());
        ResponseEntity<String> sets = rest.exchange(
                url("/api/tvac/rule-sets?articleId=" + articleId),
                HttpMethod.GET, new HttpEntity<>(auth("bootstrap", "bootstrap")), String.class);
        assertEquals(1, json.readTree(sets.getBody()).path("data").size());
    }
}
