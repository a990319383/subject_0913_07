package com.evops.constant;

/**
 * 热真空试验业务状态与标记常量。数据库统一以字符串存储。
 */
public final class TvacConst {

    private TvacConst() {
    }

    /** 试验件状态 */
    public static final class ArticleStatus {
        public static final String REGISTERED = "REGISTERED";
        public static final String IN_TEST = "IN_TEST";
        public static final String COMPLETED = "COMPLETED";
        public static final String SCRAPPED = "SCRAPPED";
        private ArticleStatus() {
        }
    }

    /** 试验计划状态 */
    public static final class PlanStatus {
        public static final String DRAFT = "DRAFT";
        public static final String ISSUED = "ISSUED";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String TERMINATED = "TERMINATED";
        private PlanStatus() {
        }
    }

    /** 遥测通道状态 */
    public static final class ChannelStatus {
        public static final String ENABLED = "ENABLED";
        public static final String DISABLED = "DISABLED";
        private ChannelStatus() {
        }
    }

    /** 通道测量量类型 */
    public static final class MeasureType {
        public static final String TEMPERATURE = "TEMPERATURE";
        public static final String PRESSURE = "PRESSURE";
        public static final String VOLTAGE = "VOLTAGE";
        public static final String CURRENT = "CURRENT";
        public static final String OTHER = "OTHER";
        private MeasureType() {
        }
    }

    /** 遥测帧越限标记 */
    public static final class LimitFlag {
        public static final String NORMAL = "NORMAL";
        public static final String HIGH = "HIGH";
        public static final String LOW = "LOW";
        private LimitFlag() {
        }
    }

    /** 判读结论 */
    public static final class Conclusion {
        public static final String PENDING = "PENDING";
        public static final String QUALIFIED = "QUALIFIED";
        public static final String UNQUALIFIED = "UNQUALIFIED";
        public static final String CONDITIONAL = "CONDITIONAL";
        private Conclusion() {
        }
    }
}
