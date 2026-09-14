package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.TvacCurvePoint;
import com.evops.vo.PlanCurveStatsVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TvacCurvePointMapper extends BaseMapper<TvacCurvePoint> {

    @Select("SELECT COALESCE(MAX(cycle_no), 0) AS max_cycle, "
            + "COUNT(*) AS curve_points, "
            + "MAX(temperature_c) AS high_temp_reached, "
            + "MIN(temperature_c) AS low_temp_reached, "
            + "MIN(pressure_pa) AS min_pressure_pa "
            + "FROM t_tvac_curve_point WHERE plan_id = #{planId}")
    PlanCurveStatsVo statsByPlan(@Param("planId") Long planId);
}
