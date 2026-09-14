package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.TvacTmFrame;
import com.evops.vo.PlanFrameStatsVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TvacTmFrameMapper extends BaseMapper<TvacTmFrame> {

    @Select("SELECT COALESCE(MAX(cycle_no), 0) AS max_cycle, "
            + "COUNT(*) AS total_frames, "
            + "COALESCE(SUM(CASE WHEN limit_flag <> 'NORMAL' THEN 1 ELSE 0 END), 0) AS abnormal_frames "
            + "FROM t_tvac_tm_frame WHERE plan_id = #{planId}")
    PlanFrameStatsVo statsByPlan(@Param("planId") Long planId);
}
