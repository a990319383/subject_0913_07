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

    /** CSV 观测记录类型 */
    public static final class RecordType {
        /** 温压曲线点 */
        public static final String CURVE = "CURVE";
        /** 遥测帧 */
        public static final String FRAME = "FRAME";
        /** 判读结论 */
        public static final String REPORT = "REPORT";
        private RecordType() {
        }
    }

    /** 逐行导入结果 */
    public static final class ImportResult {
        /** 新增成功 */
        public static final String SUCCESS = "SUCCESS";
        /** 命中业务键且未锁定，覆盖更新 */
        public static final String UPDATED = "UPDATED";
        /** 校验/锁定失败，已隔离 */
        public static final String FAILED = "FAILED";
        private ImportResult() {
        }
    }

    /** 导入分片状态 */
    public static final class ShardStatus {
        public static final String PENDING = "PENDING";
        public static final String PROCESSING = "PROCESSING";
        public static final String SUCCESS = "SUCCESS";
        /** 系统异常中断（区别于逐行业务失败），可对该片重试 */
        public static final String FAILED = "FAILED";
        private ShardStatus() {
        }
    }

    /** 导入批次状态 */
    public static final class BatchStatus {
        public static final String PROCESSING = "PROCESSING";
        /** 全部分片处理完毕（允许存在逐行失败） */
        public static final String COMPLETED = "COMPLETED";
        private BatchStatus() {
        }
    }

    /** 二进制解码帧分片状态 */
    public static final class PieceStatus {
        /** 分片校验和通过 */
        public static final String VERIFIED = "VERIFIED";
        /** 单通道坏帧：校验和不符或解码失败，隔离不影响其他通道 */
        public static final String BAD = "BAD";
        /** 地面站重复发送，校验一致，幂等丢弃 */
        public static final String DUPLICATED = "DUPLICATED";
        private PieceStatus() {
        }
    }

    /** 时序判读规则版本状态 */
    public static final class RuleVersionStatus {
        /** 草稿：区间可改，未启用 */
        public static final String DRAFT = "DRAFT";
        /** 启用：区间冻结，不能原地修改；同规则集仅一个启用版 */
        public static final String ENABLED = "ENABLED";
        /** 已停用：历史版本，区间仍冻结，供历史结果快照追溯 */
        public static final String DISABLED = "DISABLED";
        private RuleVersionStatus() {
        }
    }

    /** 业务区间（规则）类型 */
    public static final class BandType {
        /** 峰值区间 */
        public static final String PEAK = "PEAK";
        /** 平段区间 */
        public static final String FLAT = "FLAT";
        /** 谷值区间 */
        public static final String VALLEY = "VALLEY";
        private BandType() {
        }
    }

    /** 区间计算批次状态 */
    public static final class CalcRunStatus {
        /** 计算完成（同步计算，落库即完成） */
        public static final String DONE = "DONE";
        private CalcRunStatus() {
        }
    }
}
