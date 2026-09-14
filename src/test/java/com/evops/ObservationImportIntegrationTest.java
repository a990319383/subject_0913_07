package com.evops;

import com.evops.constant.TvacConst;
import com.evops.dto.ChannelCreateRequest;
import com.evops.dto.FramePieceRequest;
import com.evops.dto.FrameSessionStartRequest;
import com.evops.dto.ReportAcceptRequest;
import com.evops.dto.ReportCreateRequest;
import com.evops.dto.ReportJudgeRequest;
import com.evops.entity.TvacTmFrame;
import com.evops.service.FrameReassemblyService;
import com.evops.service.ObservationImportService;
import com.evops.service.TvacArticleService;
import com.evops.service.TvacChannelService;
import com.evops.service.TvacPlanService;
import com.evops.service.TvacReportService;
import com.evops.util.Checksums;
import com.evops.vo.FrameAssemblyVo;
import com.evops.vo.ImportErrorVo;
import com.evops.vo.ImportRowResultVo;
import com.evops.vo.ImportSummaryVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观测数据 CSV 批量导入（tvac-3）集成测试：
 * 合法/重复/缺列/坏数值混合逐行隔离、文件校验和与业务键幂等、分片处理与失败重试、
 * 已验收/已落账保护、≥50,000 行分片导入、地面站乱序帧/分片校验和/单通道坏帧隔离。
 *
 * <p>独立 imptest 内存库，不使用事务回滚（分片以 REQUIRES_NEW 独立事务提交），
 * 用例间以唯一编号隔离数据。
 */
@SpringBootTest
@ActiveProfiles("imptest")
class ObservationImportIntegrationTest {

    private static final String HEADER =
            "记录类型,对象编号,观测时间,温压曲线,遥测帧,循环次数,判读结论,来源设备";

    @Autowired
    private ObservationImportService importService;
    @Autowired
    private FrameReassemblyService reassemblyService;
    @Autowired
    private TvacArticleService articleService;
    @Autowired
    private TvacPlanService planService;
    @Autowired
    private TvacChannelService channelService;
    @Autowired
    private TvacReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------------- 基础数据夹具 ----------------

    private long setupRunningPlan(String articleCode, String planCode, String channelCode) {
        long articleId = Fixtures.newArticle(articleService, articleCode, "B-IMP");
        long planId = Fixtures.newPlan(planService, articleId, planCode);
        ChannelCreateRequest c = new ChannelCreateRequest();
        c.setChannelCode(channelCode);
        c.setChannelName("温度通道");
        c.setArticleId(articleId);
        c.setMeasureType(TvacConst.MeasureType.TEMPERATURE);
        c.setUnit("C");
        c.setUpperLimit(new BigDecimal("60"));
        c.setLowerLimit(new BigDecimal("-40"));
        channelService.create(c);
        planService.changeStatus(planId, TvacConst.PlanStatus.ISSUED);
        planService.changeStatus(planId, TvacConst.PlanStatus.RUNNING);
        return planId;
    }

    private static String curve(String article, String plan, String cycle, String offset,
                                String temp, String pressure, String device) {
        return String.join(",", "CURVE", article, "2026-01-02 10:00:00",
                plan + ";" + offset + ";" + temp + ";" + pressure, "", cycle, "", device);
    }

    private static String frame(String article, String plan, String channel, String seq,
                                String cycle, String eng, String device) {
        return String.join(",", "FRAME", article, "2026-01-02 10:00:01", "",
                plan + "|" + channel + "|" + seq + "|0xAA|" + eng, cycle, "", device);
    }

    // ---------------- 逐行隔离：合法/重复/缺列/坏数值 ----------------

    @Test
    void mixedValidDuplicateMissingColumnBadNumberRowsAreIsolatedLineByLine() {
        setupRunningPlan("ART-MIX", "PL-MIX", "C-MIX");
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        csv.append(curve("ART-MIX", "PL-MIX", "1", "100", "25.3C", "1.2E-3Pa", "GS-1")).append('\n');
        // 同一业务键（计划+循环+偏移）再来一次 -> UPDATED
        csv.append(curve("ART-MIX", "PL-MIX", "1", "100", "30.0C", "1.0E-3Pa", "GS-1")).append('\n');
        // 合法帧 -> SUCCESS
        csv.append(frame("ART-MIX", "PL-MIX", "C-MIX", "MIX-F1", "1", "25.3", "GS-1")).append('\n');
        // 同帧号重发 -> UPDATED
        csv.append(frame("ART-MIX", "PL-MIX", "C-MIX", "MIX-F1", "1", "26.0", "GS-1")).append('\n');
        // 缺列：只有 5 列
        csv.append("FRAME,ART-MIX,2026-01-02 10:00:02,,PL-MIX|C-MIX|MIX-F2|0xAA\n");
        // 坏数值：工程值非数字
        csv.append(frame("ART-MIX", "PL-MIX", "C-MIX", "MIX-F3", "1", "NOT-A-NUM", "GS-1")).append('\n');
        // 业务键不存在：未知计划
        csv.append(frame("ART-MIX", "PL-NOPE", "C-MIX", "MIX-F4", "1", "25.0", "GS-1")).append('\n');
        // 单位不支持
        csv.append(curve("ART-MIX", "PL-MIX", "1", "200", "25.3X", "1Pa", "GS-1")).append('\n');
        // 时间格式坏
        csv.append("FRAME,ART-MIX,02/01/2026 10:00,,PL-MIX|C-MIX|MIX-F5|0xAA|25.0,1,,GS-1\n");

        ImportSummaryVo summary = importService.importCsv(csv.toString(), "mix.csv", 4);

        assertEquals(TvacConst.BatchStatus.COMPLETED, summary.getStatus());
        assertEquals(9, summary.getTotalRows());
        // 成功=曲线1 + 帧1 = 2；更新=曲线重复1 + 帧重复1 = 2；失败=5
        assertEquals(2, summary.getSuccessCount(), "成功数");
        assertEquals(2, summary.getUpdatedCount(), "更新数");
        assertEquals(5, summary.getFailedCount(), "失败数");
        assertEquals(9, summary.getRows().size(), "逐行明细一条不少");
        // 不能因单行失败回滚整批：成功行已落库
        assertEquals(1, countFrames("MIX-F1"));
        assertEquals(0, countFrames("MIX-F2"));
        // 错误明细保留原始行号/字段/原值/原因
        ImportErrorVo missing = findError(summary, 6, "__ROW__");
        assertNotNull(missing);
        assertTrue(missing.getReason().contains("缺列"));
        ImportErrorVo badNum = summary.getErrors().stream()
                .filter(e -> "遥测帧".equals(e.getFieldName()))
                .filter(e -> e.getRawValue() != null && e.getRawValue().contains("NOT-A-NUM"))
                .findFirst().orElse(null);
        assertNotNull(badNum);
        assertTrue(badNum.getReason().contains("坏数值"));
        // 失败行结果标记 FAILED
        assertTrue(summary.getRows().stream()
                .filter(r -> r.getLineNo() == 6)
                .anyMatch(r -> TvacConst.ImportResult.FAILED.equals(r.getResult())));
    }

    // ---------------- 文件校验和幂等：重传同一文件直接返回原批次 ----------------

    @Test
    void sameFileChecksumIsIdempotentAndBusinessKeyBlocksDuplicateProjection() {
        setupRunningPlan("ART-IDEM", "PL-IDEM", "C-IDEM");
        String csv = HEADER + "\n"
                + frame("ART-IDEM", "PL-IDEM", "C-IDEM", "IDEM-F1", "1", "25.0", "GS-7") + "\n"
                + frame("ART-IDEM", "PL-IDEM", "C-IDEM", "IDEM-F2", "1", "26.0", "GS-7") + "\n";

        ImportSummaryVo first = importService.importCsv(csv, "id.csv", 100);
        assertFalse(first.isIdempotentHit());
        assertEquals(2, first.getSuccessCount());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_tm_frame WHERE frame_seq IN ('IDEM-F1','IDEM-F2')",
                Integer.class));

        // 同一文件（同 SHA-256）重传：幂等命中，不重复落库
        ImportSummaryVo second = importService.importCsv(csv, "id-copy.csv", 100);
        assertTrue(second.isIdempotentHit());
        assertEquals(first.getBatchId(), second.getBatchId());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_tm_frame WHERE frame_seq IN ('IDEM-F1','IDEM-F2')",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_import_batch WHERE file_checksum = ?",
                Integer.class, first.getFileChecksum()));
    }

    // ---------------- 已验收/已落账数据不得覆盖 ----------------

    @Test
    void acceptedAndPostedObservationsAreNotOverwritten() {
        long planId = setupRunningPlan("ART-LOCK", "PL-LOCK", "C-LOCK");
        String before = HEADER + "\n"
                + frame("ART-LOCK", "PL-LOCK", "C-LOCK", "LOCK-F1", "1", "25.0", "GS-9") + "\n";
        importService.importCsv(before, "lock1.csv", 100);
        assertEquals("25.0000", frameEng("LOCK-F1").toPlainString());

        // 完成计划 -> 报告 -> 判读 -> 验收 -> 落账
        planService.changeStatus(planId, TvacConst.PlanStatus.COMPLETED);
        ReportCreateRequest rc = new ReportCreateRequest();
        rc.setReportNo("RPT-LOCK-1");
        rc.setPlanId(planId);
        long reportId = reportService.create(rc).getId();
        ReportJudgeRequest judge = new ReportJudgeRequest();
        judge.setConclusion(TvacConst.Conclusion.QUALIFIED);
        judge.setJudgedBy("张工");
        reportService.judge(reportId, judge);
        ReportAcceptRequest accept = new ReportAcceptRequest();
        accept.setAcceptedBy("王总");
        reportService.accept(reportId, accept);
        reportService.post(reportId);

        // 已落账：覆盖旧帧、补录新帧、补录曲线全部逐行失败，且旧值不变
        String after = HEADER + "\n"
                + frame("ART-LOCK", "PL-LOCK", "C-LOCK", "LOCK-F1", "1", "99.0", "GS-9") + "\n"
                + frame("ART-LOCK", "PL-LOCK", "C-LOCK", "LOCK-FNEW", "1", "10.0", "GS-9") + "\n"
                + curve("ART-LOCK", "PL-LOCK", "1", "100", "1.0C", "1Pa", "GS-9") + "\n";
        ImportSummaryVo summary = importService.importCsv(after, "lock2.csv", 100);
        assertEquals(3, summary.getFailedCount());
        assertEquals(0, summary.getSuccessCount() + summary.getUpdatedCount());
        assertEquals("25.0000", frameEng("LOCK-F1").toPlainString(), "旧帧工程值不得被覆盖");
        assertEquals(0, countFrames("LOCK-FNEW"), "落账后补录的新帧必须隔离");
        assertTrue(summary.getErrors().stream().findFirst().get().getReason().contains("落账"));
    }

    // ---------------- ≥50,000 行按分片处理 ----------------

    @Test
    void atLeastFiftyThousandMixedRowsAreProcessedInShards() {
        setupRunningPlan("ART-BIG", "PL-BIG", "C-BIG");
        int total = 50_000;
        StringBuilder csv = new StringBuilder(total * 70);
        csv.append(HEADER).append('\n');
        for (int i = 0; i < total; i++) {
            if (i % 100 == 50) {
                // 500 行坏数值
                csv.append(frame("ART-BIG", "PL-BIG", "C-BIG", "BIG-F" + i, "1", "BAD", "GS-B"));
            } else if (i % 100 == 99) {
                // 500 行重复（与第 0 行同帧号）：首个为 SUCCESS，其余 UPDATED
                csv.append(frame("ART-BIG", "PL-BIG", "C-BIG", "BIG-F0", "1",
                        new BigDecimal("20").add(new BigDecimal(i % 5)).toPlainString(), "GS-B"));
            } else {
                csv.append(frame("ART-BIG", "PL-BIG", "C-BIG", "BIG-F" + i,
                        String.valueOf(1 + i % 3), "23.5", "GS-B"));
            }
            csv.append('\n');
        }

        ImportSummaryVo summary = importService.importCsv(csv.toString(), "big.csv", 5000);

        assertEquals(total, summary.getTotalRows());
        assertEquals(10, summary.getShardCount(), "50,000 行按 5000/片切成 10 片");
        assertEquals(10, summary.getShards().size());
        assertTrue(summary.getShards().stream().allMatch(s ->
                TvacConst.ShardStatus.SUCCESS.equals(s.getStatus())), "全部分片处理成功");
        assertEquals(total, summary.getRows().size(), "逐行明细 50,000 条");
        assertEquals(500, summary.getFailedCount(), "坏数值 500 行全部隔离");
        // 49,000 个不同帧号（0..49999 去掉坏值行的 500 个未使用帧号）
        Integer distinctFrames = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_tm_frame WHERE frame_seq LIKE 'BIG-F%'", Integer.class);
        // 成功帧 = 49000 唯一合法编号（坏值行编号不产生帧），重复行只更新 BIG-F0
        assertEquals(Integer.valueOf(49_000), distinctFrames);
        assertEquals(total - 500, summary.getSuccessCount() + summary.getUpdatedCount());
    }

    // ---------------- 失败分片可重试（校验和护栏 + 幂等重放） ----------------

    @Test
    void failedShardIsRetriedWithChecksumGuardAndIdempotentReplay() {
        setupRunningPlan("ART-RT", "PL-RT", "C-RT");
        String csv = HEADER + "\n"
                + frame("ART-RT", "PL-RT", "C-RT", "RT-F1", "1", "21.0", "GS-R") + "\n"
                + frame("ART-RT", "PL-RT", "C-RT", "RT-F2", "1", "22.0", "GS-R") + "\n";
        ImportSummaryVo summary = importService.importCsv(csv, "rt.csv", 1);
        assertEquals(2, summary.getShardCount());
        Long batchId = summary.getBatchId();

        // 模拟分片 1 系统失败并损坏其校验和
        String payload = jdbc.queryForObject(
                "SELECT shard_payload FROM t_tvac_import_shard WHERE batch_id=? AND shard_no=1",
                String.class, batchId);
        jdbc.update("UPDATE t_tvac_import_shard SET status='FAILED', attempts=1, "
                + "error_message='simulated crash', shard_checksum='DEADBEEF' WHERE batch_id=? AND shard_no=1",
                batchId);

        // 校验和护栏：损坏分片拒绝重跑，保持 FAILED
        ImportSummaryVo blocked = importService.retryShard(batchId, 1);
        assertEquals(TvacConst.ShardStatus.FAILED, blocked.getShards().get(0).getStatus());
        assertEquals(TvacConst.BatchStatus.PROCESSING, blocked.getStatus());

        // 修复校验和后重试：业务键幂等重放，分片 SUCCESS、批次 COMPLETED，数据不重复
        jdbc.update("UPDATE t_tvac_import_shard SET shard_checksum=? "
                + "WHERE batch_id=? AND shard_no=1", Checksums.crc32Hex(payload), batchId);
        ImportSummaryVo retried = importService.retryShard(batchId, 1);
        assertEquals(TvacConst.ShardStatus.SUCCESS, retried.getShards().get(0).getStatus());
        assertTrue(retried.getShards().get(0).getAttempts() >= 2);
        assertEquals(TvacConst.BatchStatus.COMPLETED, retried.getStatus());
        assertEquals(1, countFrames("RT-F1"));
        assertEquals(1, countFrames("RT-F2"));
    }

    // ---------------- 地面站乱序帧 / 分片校验和 / 重复 / 坏帧隔离 ----------------

    @Test
    void outOfOrderPiecesAssembleAndReassembleByPieceSequence() {
        setupRunningPlan("ART-OOO", "PL-OOO", "C-OOO-1");
        Fixtures.newChannel(channelService, "C-OOO-2", articleOf("ART-OOO"));
        Fixtures.newChannel(channelService, "C-OOO-3", articleOf("ART-OOO"));

        FrameSessionStartRequest start = new FrameSessionStartRequest();
        start.setFrameNo("LOGIC-FRAME-1");
        start.setPlanCode("PL-OOO");
        start.setExpectedPieces(3);
        start.setSourceDevice("GS-OOO");
        String sessionKey = reassemblyService.startSession(start).getSessionKey();

        // 乱序：片序 3、1、2 依次到达（分属三个通道）
        FrameAssemblyVo a3 = reassemblyService.receivePiece(
                piece(sessionKey, "C-OOO-3", 3, "payload-3", "33.0"));
        assertEquals(TvacConst.PieceStatus.VERIFIED, a3.getPieceStatus());
        FrameAssemblyVo a1 = reassemblyService.receivePiece(
                piece(sessionKey, "C-OOO-1", 1, "payload-1", "11.0"));
        // 第 3 片收齐，自动重组；落帧顺序按通道/片序，与到达顺序无关
        FrameAssemblyVo a2 = reassemblyService.receivePiece(
                piece(sessionKey, "C-OOO-2", 2, "payload-2", "22.0"));
        assertEquals(TvacConst.PieceStatus.VERIFIED, a2.getPieceStatus());

        FrameAssemblyVo assembled = a2.getStatus().equals("ASSEMBLED") ? a2
                : reassemblyService.assemble(sessionKey);
        assertEquals("ASSEMBLED", assembled.getStatus());
        assertEquals(3, assembled.getFrames().size());
        assertEquals("C-OOO-1", assembled.getFrames().get(0).getChannelCode(),
                "按通道序重组而非到达序");
        assertEquals(3, assembled.getGoodPieces());
        assertEquals(0, assembled.getBadPieces());
        // 每通道帧确实落库且带来源设备
        TvacTmFrame f = jdbc.queryForObject(
                "SELECT * FROM t_tvac_tm_frame WHERE frame_seq=?",
                (rs, n) -> {
                    TvacTmFrame frame = new TvacTmFrame();
                    frame.setFrameSeq(rs.getString("frame_seq"));
                    frame.setSourceDevice(rs.getString("source_device"));
                    return frame;
                }, "LOGIC-FRAME-1#C-OOO-2#2");
        assertNotNull(f);
        assertEquals("GS-OOO", f.getSourceDevice());
    }

    @Test
    void duplicatePiecesAreIdempotentAndSingleBadChannelPieceIsIsolated() {
        setupRunningPlan("ART-BAD", "PL-BAD", "C-BAD-1");
        Fixtures.newChannel(channelService, "C-BAD-2", articleOf("ART-BAD"));

        FrameSessionStartRequest start = new FrameSessionStartRequest();
        start.setFrameNo("LOGIC-FRAME-2");
        start.setPlanCode("PL-BAD");
        start.setSourceDevice("GS-BAD");
        String sessionKey = reassemblyService.startSession(start).getSessionKey();

        // 通道1 好片
        reassemblyService.receivePiece(piece(sessionKey, "C-BAD-1", 1, "ok-1", "20.0"));
        // 通道2 坏片（CRC 不符）
        FramePieceRequest bad = piece(sessionKey, "C-BAD-2", 1, "corrupt", "999.0");
        bad.setClientChecksum("00000000");
        FrameAssemblyVo badVo = reassemblyService.receivePiece(bad);
        assertEquals(TvacConst.PieceStatus.BAD, badVo.getPieceStatus());
        assertTrue(badVo.getBadReason().contains("校验和不符"));

        // 地面站重发通道1 同报文 -> DUPLICATED，幂等丢弃，不产生第二帧
        FrameAssemblyVo dup = reassemblyService.receivePiece(
                piece(sessionKey, "C-BAD-1", 1, "ok-1", "20.0"));
        assertEquals(TvacConst.PieceStatus.DUPLICATED, dup.getPieceStatus());

        // 通道2 用正确报文重发同一片序号 -> 坏片修复
        FrameAssemblyVo repaired = reassemblyService.receivePiece(
                piece(sessionKey, "C-BAD-2", 1, "fixed-payload", "30.0"));
        assertEquals(TvacConst.PieceStatus.VERIFIED, repaired.getPieceStatus());

        FrameAssemblyVo assembled = reassemblyService.assemble(sessionKey);
        assertEquals("ASSEMBLED", assembled.getStatus());
        assertEquals(2, assembled.getFrames().size(), "两个通道各落一帧");
        assertEquals(0, assembled.getBadPieces(), "坏片已修复");
        assertEquals(1, countFrames("LOGIC-FRAME-2#C-BAD-1#1"));
        assertEquals(1, countFrames("LOGIC-FRAME-2#C-BAD-2#1"));
    }

    @Test
    void badChannelPieceNeverBlocksOtherChannelsOnAssemble() {
        setupRunningPlan("ART-ISO", "PL-ISO", "C-ISO-1");
        Fixtures.newChannel(channelService, "C-ISO-2", articleOf("ART-ISO"));
        Fixtures.newChannel(channelService, "C-ISO-3", articleOf("ART-ISO"));

        FrameSessionStartRequest start = new FrameSessionStartRequest();
        start.setFrameNo("LOGIC-FRAME-3");
        start.setPlanCode("PL-ISO");
        String sessionKey = reassemblyService.startSession(start).getSessionKey();

        reassemblyService.receivePiece(piece(sessionKey, "C-ISO-1", 1, "p1", "10.0"));
        FramePieceRequest bad = piece(sessionKey, "C-ISO-2", 1, "xx", "0");
        bad.setClientChecksum("FFFFFFFF");
        reassemblyService.receivePiece(bad);
        reassemblyService.receivePiece(piece(sessionKey, "C-ISO-3", 1, "p3", "12.0"));

        FrameAssemblyVo assembled = reassemblyService.assemble(sessionKey);
        List<String> frameChannels = assembled.getFrames().stream()
                .map(FrameAssemblyVo.AssembledFrame::getChannelCode).collect(Collectors.toList());
        assertTrue(frameChannels.contains("C-ISO-1"));
        assertTrue(frameChannels.contains("C-ISO-3"));
        assertFalse(frameChannels.contains("C-ISO-2"), "坏通道片被隔离，不落帧");
        assertEquals(1, assembled.getBadPieceList().size());
        assertEquals(2, assembled.getGoodPieces());
        // 停用通道与坏 CRC 同等隔离（重组时再次判定）
    }

    // ---------------- helpers ----------------

    private long articleOf(String articleCode) {
        return jdbc.queryForObject("SELECT id FROM t_tvac_article WHERE article_code=?",
                Long.class, articleCode);
    }

    private FramePieceRequest piece(String sessionKey, String channelCode, int pieceSeq,
                                    String payload, String eng) {
        FramePieceRequest p = new FramePieceRequest();
        p.setSessionKey(sessionKey);
        p.setChannelCode(channelCode);
        p.setPieceSeq(pieceSeq);
        p.setPayload(payload);
        p.setClientChecksum(Checksums.crc32Hex(payload));
        p.setCycleNo(1);
        p.setEngValue(new BigDecimal(eng));
        p.setRawValue("0x" + payload);
        return p;
    }

    private int countFrames(String seq) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tvac_tm_frame WHERE frame_seq=?",
                Integer.class, seq);
    }

    private BigDecimal frameEng(String seq) {
        return jdbc.queryForObject(
                "SELECT eng_value FROM t_tvac_tm_frame WHERE frame_seq=?",
                BigDecimal.class, seq);
    }

    private ImportErrorVo findError(ImportSummaryVo summary, int lineNo, String field) {
        return summary.getErrors().stream()
                .filter(e -> e.getLineNo() == lineNo && field.equals(e.getFieldName()))
                .findFirst().orElse(null);
    }

    private ImportErrorVo findErrorByField(ImportSummaryVo summary, String field, String seq) {
        return summary.getErrors().stream()
                .filter(e -> field.equals(e.getFieldName()))
                .filter(e -> e.getRawValue() != null && e.getRawValue().contains(seq))
                .findFirst().orElse(null);
    }

    /** 业务实体夹具，避免与主测试类重复私有构造逻辑 */
    static final class Fixtures {
        static long newArticle(TvacArticleService svc, String code, String batch) {
            com.evops.dto.ArticleCreateRequest a = new com.evops.dto.ArticleCreateRequest();
            a.setArticleCode(code);
            a.setArticleName("试验件" + code);
            a.setBatchNo(batch);
            return svc.create(a).getId();
        }

        static long newPlan(TvacPlanService svc, long articleId, String code) {
            com.evops.dto.PlanCreateRequest p = new com.evops.dto.PlanCreateRequest();
            p.setPlanCode(code);
            p.setPlanName("热真空" + code);
            p.setArticleId(articleId);
            p.setHighTempC(new BigDecimal("70"));
            p.setLowTempC(new BigDecimal("-65"));
            p.setVacuumPa(new BigDecimal("0.001"));
            p.setTargetCycles(3);
            return svc.create(p).getId();
        }

        static long newChannel(TvacChannelService svc, String code, long articleId) {
            ChannelCreateRequest c = new ChannelCreateRequest();
            c.setChannelCode(code);
            c.setChannelName("温度通道" + code);
            c.setArticleId(articleId);
            c.setMeasureType(TvacConst.MeasureType.TEMPERATURE);
            c.setUnit("C");
            c.setUpperLimit(new BigDecimal("60"));
            c.setLowerLimit(new BigDecimal("-40"));
            return svc.create(c).getId();
        }
    }
}
