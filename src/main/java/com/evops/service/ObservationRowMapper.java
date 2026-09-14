package com.evops.service;

import com.evops.constant.TvacConst;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacObservation;
import com.evops.entity.TvacPlan;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacChannelMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.util.CsvReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一行 CSV 映射为归一化观测记录 {@link TvacObservation}，逐字段校验：
 * 列存在性（缺列）、枚举、数值（坏数值）、时间格式、单位、业务键可解析性与归属关系。
 * 校验失败不抛异常，而是收集字段级错误（原始行号/字段/原值/原因），由调用方逐行隔离。
 *
 * <p>表头固定列序（中文列名，首列 record_type 决定本行语义）：
 * <pre>
 * 记录类型,对象编号,观测时间,温压曲线,遥测帧,循环次数,判读结论,来源设备
 * </pre>
 * 三类记录对复合列的解析见 {@link #parseCurvePayload}/{@link #parseFramePayload}。
 */
@Component
public class ObservationRowMapper {

    /** 表头列名（顺序即列序） */
    public static final List<String> HEADERS = Arrays.asList(
            "记录类型", "对象编号", "观测时间", "温压曲线", "遥测帧",
            "循环次数", "判读结论", "来源设备");

    static final String FIELD_RECORD_TYPE = "记录类型";
    static final String FIELD_OBJECT_CODE = "对象编号";
    static final String FIELD_OBSERVE_TIME = "观测时间";
    static final String FIELD_CURVE = "温压曲线";
    static final String FIELD_FRAME = "遥测帧";
    static final String FIELD_CYCLE = "循环次数";
    static final String FIELD_CONCLUSION = "判读结论";
    static final String FIELD_DEVICE = "来源设备";

    private static final Set<String> RECORD_TYPES = new HashSet<>(Arrays.asList(
            TvacConst.RecordType.CURVE, TvacConst.RecordType.FRAME,
            TvacConst.RecordType.REPORT));
    private static final Set<String> CONCLUSIONS = new HashSet<>(Arrays.asList(
            TvacConst.Conclusion.QUALIFIED, TvacConst.Conclusion.UNQUALIFIED,
            TvacConst.Conclusion.CONDITIONAL));

    /** 支持的时间格式（地面站/试验中心两种常见口径） */
    private static final DateTimeFormatter[] TIME_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")};

    private final TvacArticleMapper articleMapper;
    private final TvacPlanMapper planMapper;
    private final TvacChannelMapper channelMapper;

    public ObservationRowMapper(TvacArticleMapper articleMapper,
                                TvacPlanMapper planMapper,
                                TvacChannelMapper channelMapper) {
        this.articleMapper = articleMapper;
        this.planMapper = planMapper;
        this.channelMapper = channelMapper;
    }

    /** 单批次内复用的字典缓存，避免 5 万行逐行查库 */
    public class Cache {
        final Map<String, TvacArticle> articleByCode = new HashMap<>();
        final Map<String, TvacPlan> planByCode = new HashMap<>();
        final Map<String, TvacChannel> channelByCode = new HashMap<>();

        TvacArticle article(String code) {
            return articleByCode.computeIfAbsent(code, c -> {
                List<TvacArticle> list = articleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TvacArticle>()
                                .eq("article_code", c));
                return list.isEmpty() ? NULL_ARTICLE : list.get(0);
            });
        }

        TvacPlan plan(String code) {
            return planByCode.computeIfAbsent(code, c -> {
                List<TvacPlan> list = planMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TvacPlan>()
                                .eq("plan_code", c));
                return list.isEmpty() ? NULL_PLAN : list.get(0);
            });
        }

        TvacChannel channel(String code) {
            return channelByCode.computeIfAbsent(code, c -> {
                List<TvacChannel> list = channelMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TvacChannel>()
                                .eq("channel_code", c));
                return list.isEmpty() ? NULL_CHANNEL : list.get(0);
            });
        }
    }

    private static final TvacArticle NULL_ARTICLE = new TvacArticle();
    private static final TvacPlan NULL_PLAN = new TvacPlan();
    private static final TvacChannel NULL_CHANNEL = new TvacChannel();

    public Cache newCache() {
        return new Cache();
    }

    public static class FieldError {
        public final String field;
        public final String rawValue;
        public final String reason;

        public FieldError(String field, String rawValue, String reason) {
            this.field = field;
            this.rawValue = rawValue;
            this.reason = reason;
        }
    }

    public static class Result {
        public TvacObservation observation;
        public java.util.List<FieldError> errors = new java.util.ArrayList<>();

        boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * @param fields  当前行字段（按表头列序）
     * @param present 该行实际解析到的列数；小于表头列数即“缺列”
     */
    public Result map(String[] fields, int present, CsvReader.CsvRow row, Cache cache) {
        Result result = new Result();
        TvacObservation obs = new TvacObservation();
        result.observation = obs;

        String type = get(fields, 0);
        String objectCode = trim(get(fields, 1));
        String timeRaw = trim(get(fields, 2));
        String curveRaw = get(fields, 3);
        String frameRaw = get(fields, 4);
        String cycleRaw = trim(get(fields, 5));
        String conclusionRaw = trim(get(fields, 6));
        String deviceRaw = trim(get(fields, 7));

        // ---- 缺列：物理列数不足（短行），结构性错误直接隔离，不再逐字段判读 ----
        if (present < HEADERS.size()) {
            result.errors.add(new FieldError("__ROW__", joinRaw(fields),
                    "列数不足：应为 " + HEADERS.size() + " 列，实际 " + present + " 列（缺列）"));
            return result;
        }

        if (type == null || type.trim().isEmpty()) {
            result.errors.add(new FieldError(FIELD_RECORD_TYPE, type, "记录类型不能为空"));
            return result;
        }
        type = type.trim();
        if (!RECORD_TYPES.contains(type)) {
            result.errors.add(new FieldError(FIELD_RECORD_TYPE, type,
                    "未知记录类型，仅支持 CURVE/FRAME/REPORT"));
            return result;
        }
        obs.setRecordType(type);
        obs.setObjectCode(objectCode);
        obs.setSourceDevice(emptyToNull(deviceRaw));

        // 循环次数（三类共用的通用列）；空值按第 1 循环处理，0 非法
        Integer cycle = null;
        if (!cycleRaw.isEmpty()) {
            cycle = parseNonNegativeInt(cycleRaw, FIELD_CYCLE, "循环次数", result);
            if (cycle != null && cycle == 0) {
                result.errors.add(new FieldError(FIELD_CYCLE, cycleRaw, "循环次数从 1 开始，不能为 0"));
                cycle = null;
            }
        }
        obs.setCycleNo(cycle);

        switch (type) {
            case TvacConst.RecordType.CURVE:
                mapCurve(obs, objectCode, timeRaw, curveRaw, cycle, cache, result);
                break;
            case TvacConst.RecordType.FRAME:
                mapFrame(obs, objectCode, timeRaw, frameRaw, cycle, cache, result);
                break;
            case TvacConst.RecordType.REPORT:
                mapReport(obs, objectCode, conclusionRaw, cache, result);
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * 温压曲线列格式：{@code 计划编号;偏移秒;温度[单位];压力[单位]}
     * 例如 {@code PL-001;120;25.3C;1.2E-3Pa}。单位可带空格，温度支持 C/F/K，压力支持 Pa/kPa。
     */
    private void mapCurve(TvacObservation obs, String objectCode, String timeRaw, String curveRaw,
                          Integer cycle, Cache cache, Result result) {
        if (objectCode == null || objectCode.isEmpty()) {
            result.errors.add(new FieldError(FIELD_OBJECT_CODE, objectCode, "对象编号不能为空"));
        }
        LocalDateTime time = parseTime(timeRaw, FIELD_OBSERVE_TIME, result, true);
        obs.setObserveTime(time);
        if (curveRaw == null || curveRaw.trim().isEmpty()) {
            result.errors.add(new FieldError(FIELD_CURVE, curveRaw, "温压曲线内容不能为空"));
            return;
        }
        String[] parts = curveRaw.trim().split(";", -1);
        if (parts.length != 4) {
            result.errors.add(new FieldError(FIELD_CURVE, curveRaw,
                    "温压曲线应为 计划编号;偏移秒;温度;压力 四段，实际 " + parts.length + " 段"));
            return;
        }
        String planCode = parts[0].trim();
        TvacPlan plan = resolvePlan(planCode, cache, result, FIELD_CURVE);
        Integer offset = parseNonNegativeInt(parts[1].trim(), FIELD_CURVE, "偏移秒", result);
        // 温度 + 单位
        UnitValue temp = parseUnitValue(parts[2], FIELD_CURVE, "温度", result,
                new HashSet<>(Arrays.asList("C", "°C", "℃", "F", "K")));
        // 压力 + 单位
        UnitValue pressure = parseUnitValue(parts[3], FIELD_CURVE, "压力", result,
                new HashSet<>(Arrays.asList("PA", "KPA", "MPA")));

        if (plan != null) {
            if (objectCode != null && !objectCode.isEmpty()) {
                TvacArticle article = resolveArticle(objectCode, cache, result, FIELD_OBJECT_CODE);
                if (article != null && !article.getId().equals(plan.getArticleId())) {
                    result.errors.add(new FieldError(FIELD_OBJECT_CODE, objectCode,
                            "对象编号与计划所属试验件不一致（计划 " + planCode + "）"));
                }
            }
            obs.setPlanId(plan.getId());
            obs.setArticleId(plan.getArticleId());
            obs.setPlanCode(planCode);
        }
        if (offset != null) {
            obs.setOffsetSec(offset);
        }
        if (temp != null) {
            obs.setTemperatureC(convertTemp(temp));
        }
        if (pressure != null) {
            obs.setPressurePa(convertPressure(pressure));
        }
        obs.setBizKey(planCode + "|" + (cycle == null ? 1 : cycle)
                + "|" + (offset == null ? "" : offset));
    }

    /**
     * 遥测帧列格式：{@code 计划编号|通道编号|帧号|原始报文|工程值}
     * 例如 {@code PL-001|C-T1|FR-0001|0x1A2B|25.3}。
     */
    private void mapFrame(TvacObservation obs, String objectCode, String timeRaw, String frameRaw,
                          Integer cycle, Cache cache, Result result) {
        if (objectCode == null || objectCode.isEmpty()) {
            result.errors.add(new FieldError(FIELD_OBJECT_CODE, objectCode, "对象编号不能为空"));
        }
        LocalDateTime time = parseTime(timeRaw, FIELD_OBSERVE_TIME, result, true);
        obs.setObserveTime(time);
        if (frameRaw == null || frameRaw.trim().isEmpty()) {
            result.errors.add(new FieldError(FIELD_FRAME, frameRaw, "遥测帧内容不能为空"));
            return;
        }
        String[] parts = frameRaw.trim().split("\\|", -1);
        if (parts.length != 5) {
            result.errors.add(new FieldError(FIELD_FRAME, frameRaw,
                    "遥测帧应为 计划编号|通道编号|帧号|原始报文|工程值 五段，实际 " + parts.length + " 段"));
            return;
        }
        String planCode = parts[0].trim();
        String channelCode = parts[1].trim();
        String frameSeq = parts[2].trim();
        String rawValue = parts[3].trim();
        String engRaw = parts[4].trim();

        if (frameSeq.isEmpty()) {
            result.errors.add(new FieldError(FIELD_FRAME, frameRaw, "帧号不能为空"));
        }
        BigDecimal eng = parseDecimal(engRaw, FIELD_FRAME, "工程值", result);

        TvacPlan plan = resolvePlan(planCode, cache, result, FIELD_FRAME);
        TvacChannel channel = resolveChannel(channelCode, cache, result, FIELD_FRAME);
        if (plan != null) {
            obs.setPlanId(plan.getId());
            obs.setArticleId(plan.getArticleId());
            obs.setPlanCode(planCode);
        }
        if (channel != null) {
            obs.setChannelId(channel.getId());
            obs.setChannelCode(channelCode);
            if (plan != null && !channel.getArticleId().equals(plan.getArticleId())) {
                result.errors.add(new FieldError(FIELD_FRAME, frameRaw,
                        "通道 " + channelCode + " 不属于计划 " + planCode + " 的试验件"));
            }
        }
        if (objectCode != null && !objectCode.isEmpty() && plan != null) {
            TvacArticle article = resolveArticle(objectCode, cache, result, FIELD_OBJECT_CODE);
            if (article != null && !article.getId().equals(plan.getArticleId())) {
                result.errors.add(new FieldError(FIELD_OBJECT_CODE, objectCode,
                        "对象编号与计划所属试验件不一致（计划 " + planCode + "）"));
            }
        }
        obs.setFrameSeq(frameSeq.isEmpty() ? null : frameSeq);
        obs.setRawValue(emptyToNull(rawValue));
        obs.setEngValue(eng);
        obs.setBizKey(frameSeq.isEmpty() ? null : frameSeq);
    }

    /** 判读结论行：对象编号=计划编号，判读结论列=QUALIFIED/UNQUALIFIED/CONDITIONAL */
    private void mapReport(TvacObservation obs, String planCode, String conclusionRaw,
                           Cache cache, Result result) {
        if (planCode == null || planCode.isEmpty()) {
            result.errors.add(new FieldError(FIELD_OBJECT_CODE, planCode, "对象编号（计划编号）不能为空"));
            return;
        }
        if (conclusionRaw == null || conclusionRaw.isEmpty()) {
            result.errors.add(new FieldError(FIELD_CONCLUSION, conclusionRaw, "判读结论不能为空"));
        } else if (!CONCLUSIONS.contains(conclusionRaw)) {
            result.errors.add(new FieldError(FIELD_CONCLUSION, conclusionRaw,
                    "判读结论仅支持 QUALIFIED/UNQUALIFIED/CONDITIONAL"));
        }
        TvacPlan plan = resolvePlan(planCode, cache, result, FIELD_OBJECT_CODE);
        if (plan != null) {
            obs.setPlanId(plan.getId());
            obs.setArticleId(plan.getArticleId());
            obs.setPlanCode(planCode);
        }
        obs.setConclusion(conclusionRaw == null ? null : conclusionRaw.trim());
        // 报告业务键 = 计划编号（一计划一报告）
        obs.setBizKey(planCode);
    }

    // ---------------- 基础解析 ----------------

    private TvacArticle resolveArticle(String code, Cache cache, Result result, String field) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        TvacArticle article = cache.article(code);
        if (article == NULL_ARTICLE || article.getId() == null) {
            result.errors.add(new FieldError(field, code, "对象编号（试验件）不存在: " + code));
            return null;
        }
        return article;
    }

    private TvacPlan resolvePlan(String code, Cache cache, Result result, String field) {
        if (code == null || code.isEmpty()) {
            result.errors.add(new FieldError(field, code, "计划编号不能为空"));
            return null;
        }
        TvacPlan plan = cache.plan(code);
        if (plan == NULL_PLAN || plan.getId() == null) {
            result.errors.add(new FieldError(field, code, "计划编号不存在: " + code));
            return null;
        }
        return plan;
    }

    private TvacChannel resolveChannel(String code, Cache cache, Result result, String field) {
        if (code == null || code.isEmpty()) {
            result.errors.add(new FieldError(field, code, "通道编号不能为空"));
            return null;
        }
        TvacChannel channel = cache.channel(code);
        if (channel == NULL_CHANNEL || channel.getId() == null) {
            result.errors.add(new FieldError(field, code, "通道编号不存在: " + code));
            return null;
        }
        return channel;
    }

    private LocalDateTime parseTime(String raw, String field, Result result, boolean required) {
        if (raw == null || raw.isEmpty()) {
            if (required) {
                result.errors.add(new FieldError(field, raw, "观测时间不能为空"));
            }
            return null;
        }
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(raw, f);
            } catch (DateTimeParseException ignore) {
                // 尝试下一种格式
            }
        }
        result.errors.add(new FieldError(field, raw,
                "时间格式不正确，支持 yyyy-MM-dd HH:mm:ss 等格式"));
        return null;
    }

    private Integer parseNonNegativeInt(String raw, String field, String label, Result result) {
        if (raw == null || raw.isEmpty()) {
            result.errors.add(new FieldError(field, raw, label + "不能为空"));
            return null;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v < 0) {
                result.errors.add(new FieldError(field, raw, label + "不能为负数: " + raw));
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            result.errors.add(new FieldError(field, raw, label + "不是合法整数（坏数值）: " + raw));
            return null;
        }
    }

    private BigDecimal parseDecimal(String raw, String field, String label, Result result) {
        if (raw == null || raw.isEmpty()) {
            result.errors.add(new FieldError(field, raw, label + "不能为空"));
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            result.errors.add(new FieldError(field, raw, label + "不是合法数值（坏数值）: " + raw));
            return null;
        }
    }

    /** 拆分数值与单位，如 "25.3C" / "1.2E-3 Pa" */
    private UnitValue parseUnitValue(String raw, String field, String label, Result result,
                                     Set<String> allowedUnits) {
        if (raw == null || raw.trim().isEmpty()) {
            result.errors.add(new FieldError(field, raw, label + "不能为空"));
            return null;
        }
        String s = raw.trim();
        int split = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean numericPart = (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+'
                    || c == 'e' || c == 'E' || c == ' ';
            if (!numericPart) {
                split = i;
                break;
            }
        }
        String numPart = s.substring(0, split).trim();
        String unitPart = s.substring(split).trim().toUpperCase();
        BigDecimal value;
        try {
            value = new BigDecimal(numPart);
        } catch (NumberFormatException e) {
            result.errors.add(new FieldError(field, raw, label + "不是合法数值（坏数值）: " + numPart));
            return null;
        }
        if (unitPart.isEmpty()) {
            result.errors.add(new FieldError(field, raw, label + "缺少单位"));
            return null;
        }
        String normalizedUnit = unitPart.replace("°", "").replace("℃", "C");
        if (!allowedUnits.contains(normalizedUnit)) {
            result.errors.add(new FieldError(field, raw,
                    label + "单位不支持: " + unitPart + "（允许 " + allowedUnits + "）"));
            return null;
        }
        return new UnitValue(value, normalizedUnit);
    }

    /** 归一化到摄氏度 */
    private BigDecimal convertTemp(UnitValue v) {
        switch (v.unit) {
            case "C":
                return v.value;
            case "F":
                // C = (F - 32) * 5/9
                return v.value.subtract(new BigDecimal("32"))
                        .multiply(new BigDecimal("5"))
                        .divide(new BigDecimal("9"), 4, BigDecimal.ROUND_HALF_UP)
                        .setScale(2, BigDecimal.ROUND_HALF_UP);
            case "K":
                // C = K - 273.15
                return v.value.subtract(new BigDecimal("273.15"))
                        .setScale(2, BigDecimal.ROUND_HALF_UP);
            default:
                return v.value;
        }
    }

    /** 归一化到 Pa */
    private BigDecimal convertPressure(UnitValue v) {
        switch (v.unit) {
            case "KPA":
                return v.value.multiply(new BigDecimal("1000")).setScale(4, BigDecimal.ROUND_HALF_UP);
            case "MPA":
                return v.value.multiply(new BigDecimal("1000000")).setScale(4, BigDecimal.ROUND_HALF_UP);
            case "PA":
            default:
                return v.value.setScale(4, BigDecimal.ROUND_HALF_UP);
        }
    }

    private static class UnitValue {
        final BigDecimal value;
        final String unit;

        UnitValue(BigDecimal value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    private static String get(String[] fields, int idx) {
        return idx < fields.length ? fields[idx] : null;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String joinRaw(String[] fields) {
        String joined = String.join(",", fields);
        return joined.length() > 900 ? joined.substring(0, 900) : joined;
    }
}
