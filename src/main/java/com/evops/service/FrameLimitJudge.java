package com.evops.service;

import com.evops.constant.TvacConst;
import com.evops.entity.TvacChannel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 按通道上下限判越限标记：高于上限 HIGH，低于下限 LOW，否则 NORMAL。
 * 手工录帧、CSV 导入、二进制分片重组三处口径保持一致。
 */
@Component
public class FrameLimitJudge {

    public String judge(BigDecimal value, TvacChannel channel) {
        if (value == null) {
            return TvacConst.LimitFlag.NORMAL;
        }
        if (channel.getUpperLimit() != null && value.compareTo(channel.getUpperLimit()) > 0) {
            return TvacConst.LimitFlag.HIGH;
        }
        if (channel.getLowerLimit() != null && value.compareTo(channel.getLowerLimit()) < 0) {
            return TvacConst.LimitFlag.LOW;
        }
        return TvacConst.LimitFlag.NORMAL;
    }
}
