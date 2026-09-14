package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.entity.TvacImportBatch;
import com.evops.entity.TvacImportError;
import com.evops.entity.TvacImportRow;
import com.evops.entity.TvacImportShard;
import com.evops.mapper.TvacImportBatchMapper;
import com.evops.mapper.TvacImportErrorMapper;
import com.evops.mapper.TvacImportRowMapper;
import com.evops.mapper.TvacImportShardMapper;
import com.evops.util.Checksums;
import com.evops.util.CsvReader;
import com.evops.vo.ImportErrorVo;
import com.evops.vo.ImportRowResultVo;
import com.evops.vo.ImportShardVo;
import com.evops.vo.ImportSummaryVo;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 观测数据 CSV 批量导入编排：
 * <ol>
 *   <li>文件 SHA-256 校验和幂等：重传同一文件直接返回原批次结果，不重复落库；</li>
 *   <li>按固定行数切分（默认 5000，保证 ≥50,000 行按分片处理），分片独立事务、
 *       独立 CRC32 校验和；</li>
 *   <li>逐行隔离：合法/重复/缺列/坏数值混合时单行失败不回滚整批，只写错误明细；</li>
 *   <li>失败分片可对原报文重试，业务键幂等保证重试不重复落库；</li>
 *   <li>已验收/已落账数据在投影层拒绝覆盖。</li>
 * </ol>
 */
@Service
public class ObservationImportService {

    /** 单分片默认行数：50,000 行至少切为 10 片 */
    public static final int DEFAULT_SHARD_SIZE = 5000;

    private final TvacImportBatchMapper batchMapper;
    private final TvacImportShardMapper shardMapper;
    private final TvacImportRowMapper rowMapper;
    private final TvacImportErrorMapper errorMapper;
    private final ImportShardProcessor shardProcessor;
    /** 自代理：createBatchAndShards 必须在独立事务中提交，不能走同类自调用 */
    private final ObservationImportService self;

    public ObservationImportService(TvacImportBatchMapper batchMapper,
                                    TvacImportShardMapper shardMapper,
                                    TvacImportRowMapper rowMapper,
                                    TvacImportErrorMapper errorMapper,
                                    ImportShardProcessor shardProcessor,
                                    @Lazy ObservationImportService self) {
        this.batchMapper = batchMapper;
        this.shardMapper = shardMapper;
        this.rowMapper = rowMapper;
        this.errorMapper = errorMapper;
        this.shardProcessor = shardProcessor;
        this.self = self;
    }

    /**
     * 导入整份 CSV。
     *
     * @param content   CSV 全文（首行表头）
     * @param fileName  原始文件名（仅留痕）
     * @param shardSize 分片行数，null 取默认 5000
     */
    public ImportSummaryVo importCsv(String content, String fileName, Integer shardSize) {
        if (shardSize == null || shardSize <= 0) {
            shardSize = DEFAULT_SHARD_SIZE;
        }
        String checksum = Checksums.sha256Hex(content);

        // 文件级幂等：同一校验和的文件直接返回首次结果
        TvacImportBatch existing = batchMapper.selectOne(
                new QueryWrapper<TvacImportBatch>().eq("file_checksum", checksum));
        if (existing != null) {
            ImportSummaryVo vo = buildSummary(existing);
            vo.setIdempotentHit(true);
            return vo;
        }

        CsvReader.CsvDocument doc = CsvReader.parse(content);
        validateHeader(doc.getHeader());

        List<CsvReader.CsvRow> rows = doc.getRows();
        TvacImportBatch batch = self.createBatchAndShards(fileName, checksum, rows, shardSize);

        processAllShards(batch);
        batch = batchMapper.selectById(batch.getId());
        return buildSummary(batch);
    }

    /** 失败分片重试：用落库的原报文重跑，业务键幂等保证不重复落数据 */
    public ImportSummaryVo retryShard(Long batchId, Integer shardNo) {
        TvacImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BusinessException.of("导入批次不存在: " + batchId);
        }
        TvacImportShard shard = shardMapper.selectOne(new QueryWrapper<TvacImportShard>()
                .eq("batch_id", batchId).eq("shard_no", shardNo));
        if (shard == null) {
            throw BusinessException.of("分片不存在: 批次 " + batchId + " 分片 " + shardNo);
        }
        if (!TvacConst.ShardStatus.FAILED.equals(shard.getStatus())
                && !TvacConst.ShardStatus.PENDING.equals(shard.getStatus())) {
            throw BusinessException.of("分片状态为 " + shard.getStatus()
                    + "，仅 FAILED/PENDING 分片可重试");
        }
        invokeShard(shard);
        refreshBatch(batchId);
        return buildSummary(batchMapper.selectById(batchId));
    }

    /** 建批次与分片元数据（一个短事务提交，随后各分片独立事务处理） */
    @Transactional(rollbackFor = Exception.class)
    public TvacImportBatch createBatchAndShards(String fileName, String checksum,
                                                List<CsvReader.CsvRow> rows, int shardSize) {
        TvacImportBatch batch = new TvacImportBatch();
        // 批次号由文件校验和确定性派生：即便同文件并发首传，校验和唯一约束也会兜底
        batch.setBatchNo("IMP-" + checksum.substring(0, 12).toUpperCase());
        batch.setFileName(fileName);
        batch.setFileChecksum(checksum);
        batch.setTotalRows(rows.size());
        batch.setShardSize(shardSize);
        batch.setSuccessCount(0);
        batch.setUpdatedCount(0);
        batch.setFailedCount(0);
        batch.setStatus(TvacConst.BatchStatus.PROCESSING);
        batchMapper.insert(batch);

        int shardCount = (rows.size() + shardSize - 1) / shardSize;
        batch.setShardCount(Math.max(shardCount, 0));
        if (rows.isEmpty()) {
            batch.setStatus(TvacConst.BatchStatus.COMPLETED);
            batchMapper.updateById(batch);
            return batch;
        }
        for (int s = 0; s < shardCount; s++) {
            int from = s * shardSize;
            int to = Math.min(from + shardSize, rows.size());
            StringBuilder payload = new StringBuilder();
            for (int i = from; i < to; i++) {
                payload.append(stableLine(rows.get(i).getFields()));
            }
            String payloadText = payload.toString();
            TvacImportShard shard = new TvacImportShard();
            shard.setBatchId(batch.getId());
            shard.setShardNo(s + 1);
            shard.setLineStart(rows.get(from).getLineNo());
            shard.setLineEnd(rows.get(to - 1).getLineNo());
            shard.setRowCount(to - from);
            shard.setShardChecksum(Checksums.crc32Hex(payloadText));
            shard.setShardPayload(payloadText);
            shard.setStatus(TvacConst.ShardStatus.PENDING);
            shard.setAttempts(0);
            shardMapper.insert(shard);
        }
        batchMapper.updateById(batch);
        return batch;
    }

    /** 逐片处理：单片系统异常只标记该片 FAILED，不阻断其余分片 */
    private void processAllShards(TvacImportBatch batch) {
        List<TvacImportShard> shards = shardMapper.selectList(
                new QueryWrapper<TvacImportShard>()
                        .eq("batch_id", batch.getId()).orderByAsc("shard_no"));
        for (TvacImportShard shard : shards) {
            invokeShard(shard);
        }
        refreshBatch(batch.getId());
    }

    private void invokeShard(TvacImportShard shard) {
        try {
            shardProcessor.process(shard.getId());
        } catch (Exception e) {
            // 系统异常：分片事务已回滚，在独立事务中标记 FAILED 等待重试
            try {
                shardProcessor.markFailed(shard.getId(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignore) {
                // 标记失败本身不再抛出，保证其他分片继续
            }
        }
    }

    /** 汇总各分片计数，决定批次状态；任一 FAILED 片则批次保持 PROCESSING */
    private void refreshBatch(Long batchId) {
        List<TvacImportShard> shards = shardMapper.selectList(
                new QueryWrapper<TvacImportShard>().eq("batch_id", batchId));
        int success = 0;
        int updated = 0;
        int failed = 0;
        List<Integer> failedShards = new ArrayList<>();
        boolean anyUnfinished = false;
        for (TvacImportShard s : shards) {
            if (TvacConst.ShardStatus.FAILED.equals(s.getStatus())
                    || TvacConst.ShardStatus.PENDING.equals(s.getStatus())
                    || TvacConst.ShardStatus.PROCESSING.equals(s.getStatus())) {
                anyUnfinished = true;
                if (TvacConst.ShardStatus.FAILED.equals(s.getStatus())) {
                    failedShards.add(s.getShardNo());
                }
            }
            success += nz(s.getSuccessCount());
            updated += nz(s.getUpdatedCount());
            failed += nz(s.getFailedCount());
        }
        TvacImportBatch batch = batchMapper.selectById(batchId);
        batch.setSuccessCount(success);
        batch.setUpdatedCount(updated);
        batch.setFailedCount(failed);
        if (anyUnfinished) {
            batch.setStatus(TvacConst.BatchStatus.PROCESSING);
            batch.setErrorMessage(failedShards.isEmpty() ? null
                    : "存在系统失败分片，可重试: " + failedShards);
        } else {
            batch.setStatus(TvacConst.BatchStatus.COMPLETED);
            batch.setErrorMessage(null);
        }
        batchMapper.updateById(batch);
    }

    public ImportSummaryVo getSummary(Long batchId) {
        TvacImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BusinessException.of("导入批次不存在: " + batchId);
        }
        return buildSummary(batch);
    }

    private ImportSummaryVo buildSummary(TvacImportBatch batch) {
        ImportSummaryVo vo = new ImportSummaryVo();
        vo.setBatchId(batch.getId());
        vo.setBatchNo(batch.getBatchNo());
        vo.setFileName(batch.getFileName());
        vo.setFileChecksum(batch.getFileChecksum());
        vo.setStatus(batch.getStatus());
        vo.setIdempotentHit(false);
        vo.setShardSize(nz(batch.getShardSize()));
        vo.setShardCount(nz(batch.getShardCount()));
        vo.setTotalRows(nz(batch.getTotalRows()));
        vo.setSuccessCount(nz(batch.getSuccessCount()));
        vo.setUpdatedCount(nz(batch.getUpdatedCount()));
        vo.setFailedCount(nz(batch.getFailedCount()));

        List<ImportShardVo> shardVos = new ArrayList<>();
        for (TvacImportShard s : shardMapper.selectList(new QueryWrapper<TvacImportShard>()
                .eq("batch_id", batch.getId()).orderByAsc("shard_no"))) {
            ImportShardVo sv = new ImportShardVo();
            sv.setId(s.getId());
            sv.setShardNo(s.getShardNo());
            sv.setLineStart(s.getLineStart());
            sv.setLineEnd(s.getLineEnd());
            sv.setRowCount(nz(s.getRowCount()));
            sv.setSuccessCount(nz(s.getSuccessCount()));
            sv.setUpdatedCount(nz(s.getUpdatedCount()));
            sv.setFailedCount(nz(s.getFailedCount()));
            sv.setShardChecksum(s.getShardChecksum());
            sv.setStatus(s.getStatus());
            sv.setAttempts(nz(s.getAttempts()));
            sv.setErrorMessage(s.getErrorMessage());
            shardVos.add(sv);
        }
        vo.setShards(shardVos);

        List<ImportRowResultVo> rowVos = new ArrayList<>();
        for (TvacImportRow r : rowMapper.selectList(new QueryWrapper<TvacImportRow>()
                .eq("batch_id", batch.getId()).orderByAsc("line_no").orderByAsc("id"))) {
            ImportRowResultVo rv = new ImportRowResultVo();
            rv.setShardNo(r.getShardNo());
            rv.setLineNo(r.getLineNo());
            rv.setRecordType(r.getRecordType());
            rv.setBizKey(r.getBizKey());
            rv.setObjectCode(r.getObjectCode());
            rv.setResult(r.getResult());
            rv.setTargetTable(r.getTargetTable());
            rv.setTargetId(r.getTargetId());
            rowVos.add(rv);
        }
        vo.setRows(rowVos);

        List<ImportErrorVo> errorVos = new ArrayList<>();
        for (TvacImportError e : errorMapper.selectList(new QueryWrapper<TvacImportError>()
                .eq("batch_id", batch.getId()).orderByAsc("line_no").orderByAsc("id"))) {
            ImportErrorVo ev = new ImportErrorVo();
            ev.setShardNo(e.getShardNo());
            ev.setLineNo(e.getLineNo());
            ev.setRecordType(e.getRecordType());
            ev.setBizKey(e.getBizKey());
            ev.setFieldName(e.getFieldName());
            ev.setRawValue(e.getRawValue());
            ev.setReason(e.getReason());
            errorVos.add(ev);
        }
        vo.setErrors(errorVos);
        return vo;
    }

    private void validateHeader(String[] header) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < ObservationRowMapper.HEADERS.size(); i++) {
            String expected = ObservationRowMapper.HEADERS.get(i);
            String actual = i < header.length ? header[i].trim() : null;
            if (!expected.equals(actual)) {
                missing.add(expected);
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.of("CSV 表头与约定不符，缺少/错位列: " + missing
                    + "；标准表头：" + ObservationRowMapper.HEADERS);
        }
    }

    /**
     * 把一行字段序列化为“稳定单行”报文：字段内换行/回车替换为空格，
     * 保证重试解析时“一条记录 = 一个物理行”，原始行号可由分片起始行确定性还原。
     */
    static String stableLine(String[] fields) {
        String[] sanitized = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            String v = fields[i] == null ? "" : fields[i];
            sanitized[i] = v.replace('\r', ' ').replace('\n', ' ');
        }
        return CsvReader.toLine(sanitized);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
