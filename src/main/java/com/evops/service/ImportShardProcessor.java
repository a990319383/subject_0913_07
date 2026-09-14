package com.evops.service;

import com.evops.constant.TvacConst;
import com.evops.entity.TvacImportError;
import com.evops.entity.TvacImportRow;
import com.evops.entity.TvacImportShard;
import com.evops.entity.TvacObservation;
import com.evops.mapper.TvacImportShardMapper;
import com.evops.util.Checksums;
import com.evops.util.CsvReader;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片处理器：一个分片一个独立事务，分片内逐行隔离。
 *
 * <p>逐行隔离：单行映射失败（缺列/坏数值/业务键不存在/锁定）只写错误明细与 FAILED 行，
 * 不影响同片其他行；只有未预期的系统异常才让整片事务回滚，分片标记 FAILED 后可重试。
 */
@Component
public class ImportShardProcessor {

    private final TvacImportShardMapper shardMapper;
    private final ObservationRowMapper rowMapper;
    private final ObservationProjector projector;
    private final JdbcTemplate jdbcTemplate;

    public ImportShardProcessor(TvacImportShardMapper shardMapper,
                                ObservationRowMapper rowMapper,
                                ObservationProjector projector,
                                JdbcTemplate jdbcTemplate) {
        this.shardMapper = shardMapper;
        this.rowMapper = rowMapper;
        this.projector = projector;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 单个分片的处理计数（在分片事务内累计） */
    public static class Counts {
        public int total;
        public int success;
        public int updated;
        public int failed;
    }

    /**
     * 处理一个分片。独立事务（REQUIRES_NEW）：提交粒度为整片，逐行业务失败不回滚，
     * 系统异常整片回滚。无论被批量编排还是单分片重试调用，事务边界都一致。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Counts process(Long shardId) {
        TvacImportShard shard = shardMapper.selectById(shardId);
        if (shard == null) {
            throw new IllegalArgumentException("分片不存在: " + shardId);
        }
        // 分片校验和：防止重试串片/报文损坏
        String recomputed = Checksums.crc32Hex(shard.getShardPayload());
        if (!Checksums.matches(shard.getShardChecksum(), recomputed)) {
            throw new IllegalStateException("分片 " + shard.getShardNo()
                    + " 校验和不符，报文可能损坏或串片，拒绝处理");
        }
        shard.setStatus(TvacConst.ShardStatus.PROCESSING);
        shard.setAttempts(shard.getAttempts() == null ? 1 : shard.getAttempts() + 1);
        shardMapper.updateById(shard);

        // 重试幂等：业务数据靠业务键台账去重，逐行/错误明细先清掉本片旧留痕再重写，
        // 使“失败分片重试”在审计层也是恰好一次。
        jdbcTemplate.update("DELETE FROM t_tvac_import_row WHERE batch_id=? AND shard_no=?",
                shard.getBatchId(), shard.getShardNo());
        jdbcTemplate.update("DELETE FROM t_tvac_import_error WHERE batch_id=? AND shard_no=?",
                shard.getBatchId(), shard.getShardNo());

        List<CsvReader.CsvRow> rows =
                CsvReader.parseShardPayload(shard.getShardPayload(), shard.getLineStart());
        ObservationRowMapper.Cache cache = rowMapper.newCache();
        Counts counts = new Counts();
        counts.total = rows.size();

        List<Object[]> rowArgs = new ArrayList<>();
        List<Object[]> errorArgs = new ArrayList<>();

        for (CsvReader.CsvRow row : rows) {
            processOne(shard, row, cache, counts, rowArgs, errorArgs);
        }

        // 明细批量落库（同片事务）
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_tvac_import_row "
                        + "(batch_id, shard_no, line_no, record_type, biz_key, object_code, "
                        + "result, target_table, target_id, create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                rowArgs);
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_tvac_import_error "
                        + "(batch_id, shard_no, line_no, record_type, biz_key, "
                        + "field_name, raw_value, reason, create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                errorArgs);
        shard.setRowCount(counts.total);
        shard.setSuccessCount(counts.success);
        shard.setUpdatedCount(counts.updated);
        shard.setFailedCount(counts.failed);
        shard.setStatus(TvacConst.ShardStatus.SUCCESS);
        shard.setErrorMessage(null);
        shardMapper.updateById(shard);
        return counts;
    }

    private void processOne(TvacImportShard shard, CsvReader.CsvRow row,
                            ObservationRowMapper.Cache cache, Counts counts,
                            List<Object[]> rowArgs, List<Object[]> errorArgs) {
        int lineNo = row.getLineNo();
        String[] fields = row.getFields();
        ObservationRowMapper.Result mapped = null;
        try {
            mapped = rowMapper.map(fields, fields.length, row, cache);
            if (!mapped.ok()) {
                failRow(shard, lineNo, null, null, null, mapped, counts, rowArgs, errorArgs);
                return;
            }
            TvacObservation obs = mapped.observation;
            ObservationProjector.Outcome outcome = projector.project(obs, shard.getBatchId());
            counts.success += TvacConst.ImportResult.UPDATED.equals(outcome.result) ? 0 : 1;
            counts.updated += TvacConst.ImportResult.UPDATED.equals(outcome.result) ? 1 : 0;
            rowArgs.add(new Object[]{
                    shard.getBatchId(), shard.getShardNo(), lineNo,
                    obs.getRecordType(), obs.getBizKey(), obs.getObjectCode(),
                    outcome.result, outcome.targetTable, outcome.targetId});
        } catch (ObservationProjector.RejectException | DuplicateKeyException
                | IllegalArgumentException e) {
            // 业务拒绝 / 唯一键冲突：逐行隔离
            failRow(shard, lineNo,
                    mapped == null ? null : mapped.observation == null ? null : mapped.observation.getRecordType(),
                    mapped == null || mapped.observation == null ? null : mapped.observation.getBizKey(),
                    e.getMessage(), mapped, counts, rowArgs, errorArgs);
        }
    }

    private void failRow(TvacImportShard shard, int lineNo, String recordType, String bizKey,
                         String extraReason, ObservationRowMapper.Result mapped, Counts counts,
                         List<Object[]> rowArgs, List<Object[]> errorArgs) {
        counts.failed++;
        rowArgs.add(new Object[]{
                shard.getBatchId(), shard.getShardNo(), lineNo,
                recordType, bizKey,
                mapped == null || mapped.observation == null ? null : mapped.observation.getObjectCode(),
                TvacConst.ImportResult.FAILED, null, null});

        if (mapped != null) {
            for (ObservationRowMapper.FieldError fe : mapped.errors) {
                errorArgs.add(new Object[]{
                        shard.getBatchId(), shard.getShardNo(), lineNo, recordType, bizKey,
                        fe.field, truncate(fe.rawValue), truncate(fe.reason)});
            }
        }
        if (extraReason != null && !extraReason.isEmpty()) {
            errorArgs.add(new Object[]{
                    shard.getBatchId(), shard.getShardNo(), lineNo, recordType, bizKey,
                    "__BUSINESS__", null, truncate(extraReason)});
        }
    }

    /** 系统异常后在新事务里把分片标记 FAILED（原处理事务已回滚） */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long shardId, String message) {
        TvacImportShard shard = shardMapper.selectById(shardId);
        if (shard == null) {
            return;
        }
        shard.setStatus(TvacConst.ShardStatus.FAILED);
        shard.setErrorMessage(truncate(message));
        shardMapper.updateById(shard);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 900 ? s.substring(0, 900) : s;
    }
}
