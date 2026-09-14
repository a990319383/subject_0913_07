package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 区间计算批次：同一（规则版本 + UTC 窗口）唯一。
 * 计算采用的规则区间以 snapshotJson 快照留痕，历史结果读取以快照为准，
 * 规则换版不污染既有批次；并发重算由 (version_id, window_start_utc, window_end_utc)
 * 唯一约束兜底，不会产生两份结果。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_calc_run")
public class TvacCalcRun extends BaseEntity {
    /** 批次号（业务唯一键，按范围确定性生成） */
    private String runNo;
    private Long setId;
    private Long versionId;
    private Integer versionNo;
    private Long articleId;
    /** 计算窗口起点（设备 UTC，含） */
    private LocalDateTime windowStartUtc;
    /** 计算窗口终点（设备 UTC，不含） */
    private LocalDateTime windowEndUtc;
    /** 计算时采用的任务时区（冗余自规则集） */
    private String missionTz;
    /** 采用的规则区间快照（JSON） */
    private String snapshotJson;
    /** 窗口内参与归类的观测总数 */
    private Integer obsCount;
    /** DONE */
    private String status;
}
