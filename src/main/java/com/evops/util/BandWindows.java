package com.evops.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 业务区间（时序规则）时间工具。
 *
 * <p>试验观测时间以设备 UTC 为源；归类时换算到任务时区取当日分钟，
 * 区间一律左闭右开 [startMin, endMin)，endMin &lt;= startMin 表示跨日区间
 * （如 22:00 - 次日 10:00）。同一规则版本内的区间按环形（1440 分钟为一昼夜）
 * 判定重叠，重叠必须拒绝。
 */
public final class BandWindows {

    /** 一昼夜分钟数 */
    public static final int MINUTES_PER_DAY = 1440;

    private BandWindows() {
    }

    /** 设备 UTC 时刻换算为任务时区的当日分钟（0..1439） */
    public static int minuteOfDay(LocalDateTime utcTime, ZoneId missionZone) {
        LocalDateTime mission = utcTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(missionZone).toLocalDateTime();
        return mission.getHour() * 60 + mission.getMinute();
    }

    /** 设备 UTC 时刻换算为任务时区的本地时间（用于按任务时区展示） */
    public static LocalDateTime toMissionTime(LocalDateTime utcTime, ZoneId missionZone) {
        return utcTime.atZone(ZoneOffset.UTC).withZoneSameInstant(missionZone).toLocalDateTime();
    }

    /**
     * 当日分钟 minute 是否落在区间 [startMin, endMin) 内。
     * 左闭右开；endMin &lt;= startMin 时按跨日处理（含起点侧深夜与终点侧凌晨）。
     */
    public static boolean contains(int startMin, int endMin, int minute) {
        if (endMin > startMin) {
            return minute >= startMin && minute < endMin;
        }
        // 跨日：[startMin, 1440) ∪ [0, endMin)
        return minute >= startMin || minute < endMin;
    }

    /**
     * 两个区间在环形时间轴（一昼夜 1440 分钟）上是否重叠。
     * 相邻端点不算重叠（左闭右开语义：[0,600) 与 [600,1200) 不重叠）。
     */
    public static boolean overlaps(int s1, int e1, int s2, int e2) {
        // 展开为线性区间：跨日区间终点顺延一昼夜
        long a1 = s1;
        long b1 = linearEnd(s1, e1);
        long a2 = s2;
        long b2 = linearEnd(s2, e2);
        return linearOverlap(a1, b1, a2, b2)
                || linearOverlap(a1, b1, a2 + MINUTES_PER_DAY, b2 + MINUTES_PER_DAY)
                || linearOverlap(a1 + MINUTES_PER_DAY, b1 + MINUTES_PER_DAY, a2, b2);
    }

    /** 校验区间端点：0 &lt;= start &lt; 1440，0 &lt; end &lt;= 1440，start != end */
    public static void checkRange(int startMin, int endMin) {
        if (startMin < 0 || startMin >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("区间起始分钟须在 [0,1439]: " + startMin);
        }
        if (endMin <= 0 || endMin > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("区间结束分钟须在 [1,1440]: " + endMin);
        }
        if (startMin == endMin) {
            throw new IllegalArgumentException("区间起止分钟不能相同: " + startMin);
        }
    }

    /** 跨日区间展开为线性终点（endMin &lt;= startMin 时 +1440） */
    private static long linearEnd(int startMin, int endMin) {
        return endMin > startMin ? endMin : (long) endMin + MINUTES_PER_DAY;
    }

    /** 线性区间重叠（左闭右开，端点相接不算重叠） */
    private static boolean linearOverlap(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }
}
