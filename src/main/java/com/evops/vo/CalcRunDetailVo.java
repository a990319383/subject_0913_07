package com.evops.vo;

import com.evops.entity.TvacCalcDetail;
import com.evops.entity.TvacCalcRun;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 区间计算批次详情：批次 + 逐区间计算明细。
 * 窗口同时给出设备 UTC（源）与任务时区（展示）两种表示；
 * 明细区间取自计算当时采用的版本快照，规则换版后本详情不变。
 */
@Data
public class CalcRunDetailVo {
    private TvacCalcRun run;
    private List<TvacCalcDetail> details;
    /** 任务时区（IANA ID） */
    private String missionTz;
    /** 窗口起点（任务时区展示） */
    private LocalDateTime windowStartMission;
    /** 窗口终点（任务时区展示） */
    private LocalDateTime windowEndMission;
}
