package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.RuleBandRequest;
import com.evops.dto.RuleSetCreateRequest;
import com.evops.dto.RuleVersionCreateRequest;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacRuleBand;
import com.evops.entity.TvacRuleSet;
import com.evops.entity.TvacRuleVersion;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacRuleBandMapper;
import com.evops.mapper.TvacRuleSetMapper;
import com.evops.mapper.TvacRuleVersionMapper;
import com.evops.util.BandWindows;
import com.evops.vo.RuleVersionVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 时序判读规则维护：规则集（对象 + 任务时区）+ 版本化业务区间。
 *
 * <p>版本化约束：版本号集内递增；DRAFT 可改区间；启用后区间冻结、不能原地修改，
 * 换版只能新建版本；同规则集任一时刻至多一个 ENABLED 版本（启用新版自动停用旧版）。
 * 区间一律左闭右开、支持跨日，同版本内区间重叠必须拒绝。
 */
@Service
public class TvacRuleService {

    private static final Set<String> BAND_TYPES = new HashSet<>(Arrays.asList(
            TvacConst.BandType.PEAK,
            TvacConst.BandType.FLAT,
            TvacConst.BandType.VALLEY));

    private final TvacRuleSetMapper ruleSetMapper;
    private final TvacRuleVersionMapper ruleVersionMapper;
    private final TvacRuleBandMapper ruleBandMapper;
    private final TvacArticleMapper articleMapper;

    public TvacRuleService(TvacRuleSetMapper ruleSetMapper,
                           TvacRuleVersionMapper ruleVersionMapper,
                           TvacRuleBandMapper ruleBandMapper,
                           TvacArticleMapper articleMapper) {
        this.ruleSetMapper = ruleSetMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.ruleBandMapper = ruleBandMapper;
        this.articleMapper = articleMapper;
    }

    /** 建档规则集：对象必须存在，任务时区必须是合法 IANA 时区 */
    public TvacRuleSet createSet(RuleSetCreateRequest req) {
        TvacArticle article = articleMapper.selectById(req.getArticleId());
        if (article == null) {
            throw BusinessException.of("试验件不存在: " + req.getArticleId());
        }
        try {
            ZoneId.of(req.getMissionTz());
        } catch (Exception e) {
            throw BusinessException.of("非法任务时区: " + req.getMissionTz());
        }
        TvacRuleSet set = new TvacRuleSet();
        set.setSetCode(req.getSetCode());
        set.setSetName(req.getSetName());
        set.setArticleId(req.getArticleId());
        set.setMissionTz(req.getMissionTz());
        set.setStatus("ENABLED");
        set.setRemark(req.getRemark());
        ruleSetMapper.insert(set);
        return set;
    }

    public TvacRuleSet getSet(Long id) {
        TvacRuleSet set = ruleSetMapper.selectById(id);
        if (set == null) {
            throw BusinessException.of("规则集不存在: " + id);
        }
        return set;
    }

    public List<TvacRuleSet> listSets(Long articleId) {
        QueryWrapper<TvacRuleSet> qw = new QueryWrapper<>();
        if (articleId != null) {
            qw.eq("article_id", articleId);
        }
        qw.orderByAsc("id");
        return ruleSetMapper.selectList(qw);
    }

    /**
     * 新建规则版本（DRAFT）：版本号取集内最大 + 1，区间随版本一并落库。
     * 区间校验失败整版不落库。
     */
    @Transactional
    public RuleVersionVo createVersion(Long setId, RuleVersionCreateRequest req) {
        TvacRuleSet set = getSet(setId);
        validateBands(req.getBands());
        Integer maxNo = ruleVersionMapper.selectList(
                        new QueryWrapper<TvacRuleVersion>().eq("set_id", set.getId()))
                .stream().map(TvacRuleVersion::getVersionNo).mapToInt(Integer::intValue)
                .max().orElse(0);
        TvacRuleVersion version = new TvacRuleVersion();
        version.setSetId(set.getId());
        version.setVersionNo(maxNo + 1);
        version.setStatus(TvacConst.RuleVersionStatus.DRAFT);
        version.setRemark(req.getRemark());
        ruleVersionMapper.insert(version);
        insertBands(version.getId(), req.getBands());
        return getVersion(version.getId());
    }

    public RuleVersionVo getVersion(Long versionId) {
        TvacRuleVersion version = ruleVersionMapper.selectById(versionId);
        if (version == null) {
            throw BusinessException.of("规则版本不存在: " + versionId);
        }
        RuleVersionVo vo = new RuleVersionVo();
        vo.setVersion(version);
        vo.setBands(listBands(versionId));
        return vo;
    }

    public List<RuleVersionVo> listVersions(Long setId) {
        getSet(setId);
        List<TvacRuleVersion> versions = ruleVersionMapper.selectList(
                new QueryWrapper<TvacRuleVersion>()
                        .eq("set_id", setId).orderByAsc("version_no"));
        List<RuleVersionVo> result = new ArrayList<>();
        for (TvacRuleVersion v : versions) {
            RuleVersionVo vo = new RuleVersionVo();
            vo.setVersion(v);
            vo.setBands(listBands(v.getId()));
            result.add(vo);
        }
        return result;
    }

    /**
     * 整体替换版本区间：仅 DRAFT 允许；启用后不能原地修改，须新建版本。
     */
    @Transactional
    public RuleVersionVo replaceBands(Long versionId, List<RuleBandRequest> bands) {
        TvacRuleVersion version = requireVersion(versionId);
        if (!TvacConst.RuleVersionStatus.DRAFT.equals(version.getStatus())) {
            throw BusinessException.of("规则版本已启用，区间冻结不能原地修改，请新建版本；当前状态: "
                    + version.getStatus());
        }
        validateBands(bands);
        ruleBandMapper.delete(new QueryWrapper<TvacRuleBand>().eq("version_id", versionId));
        insertBands(versionId, bands);
        return getVersion(versionId);
    }

    /**
     * 启用版本：区间须已定义；同规则集其他启用版自动停用。
     * 启用后版本区间冻结，历史计算结果仍引用本版本快照。
     */
    @Transactional
    public TvacRuleVersion enable(Long versionId) {
        TvacRuleVersion version = requireVersion(versionId);
        if (TvacConst.RuleVersionStatus.ENABLED.equals(version.getStatus())) {
            throw BusinessException.of("规则版本已处于启用状态: " + versionId);
        }
        if (listBands(versionId).isEmpty()) {
            throw BusinessException.of("规则版本未定义业务区间，不能启用: " + versionId);
        }
        // 同集仅一个启用版：其余 ENABLED 版本自动停用（历史版本保留，不删不改）
        List<TvacRuleVersion> siblings = ruleVersionMapper.selectList(
                new QueryWrapper<TvacRuleVersion>()
                        .eq("set_id", version.getSetId())
                        .eq("status", TvacConst.RuleVersionStatus.ENABLED));
        for (TvacRuleVersion s : siblings) {
            s.setStatus(TvacConst.RuleVersionStatus.DISABLED);
            ruleVersionMapper.updateById(s);
        }
        version.setStatus(TvacConst.RuleVersionStatus.ENABLED);
        version.setEnabledTime(LocalDateTime.now());
        ruleVersionMapper.updateById(version);
        return version;
    }

    /** 停用版本：仅 ENABLED 可停用；停用版区间仍冻结，供历史快照追溯 */
    public TvacRuleVersion disable(Long versionId) {
        TvacRuleVersion version = requireVersion(versionId);
        if (!TvacConst.RuleVersionStatus.ENABLED.equals(version.getStatus())) {
            throw BusinessException.of("只有启用中的规则版本可以停用，当前状态: " + version.getStatus());
        }
        version.setStatus(TvacConst.RuleVersionStatus.DISABLED);
        ruleVersionMapper.updateById(version);
        return version;
    }

    /** 删除版本：仅 DRAFT 可删；已启用过的版本（含停用）作为历史永久保留 */
    @Transactional
    public void deleteVersion(Long versionId) {
        TvacRuleVersion version = requireVersion(versionId);
        if (!TvacConst.RuleVersionStatus.DRAFT.equals(version.getStatus())) {
            throw BusinessException.of("已启用过的规则版本是历史档案，不能删除；当前状态: "
                    + version.getStatus());
        }
        ruleBandMapper.delete(new QueryWrapper<TvacRuleBand>().eq("version_id", versionId));
        ruleVersionMapper.deleteById(versionId);
    }

    /** 当前启用版本（同集至多一个）；无则返回 null */
    public TvacRuleVersion enabledVersionOf(Long setId) {
        return ruleVersionMapper.selectOne(new QueryWrapper<TvacRuleVersion>()
                .eq("set_id", setId)
                .eq("status", TvacConst.RuleVersionStatus.ENABLED)
                .orderByDesc("version_no").last("LIMIT 1"));
    }

    public List<TvacRuleBand> listBands(Long versionId) {
        return ruleBandMapper.selectList(new QueryWrapper<TvacRuleBand>()
                .eq("version_id", versionId).orderByAsc("id"));
    }

    private TvacRuleVersion requireVersion(Long versionId) {
        TvacRuleVersion version = ruleVersionMapper.selectById(versionId);
        if (version == null) {
            throw BusinessException.of("规则版本不存在: " + versionId);
        }
        return version;
    }

    private void insertBands(Long versionId, List<RuleBandRequest> bands) {
        for (RuleBandRequest b : bands) {
            TvacRuleBand band = new TvacRuleBand();
            band.setVersionId(versionId);
            band.setBandType(b.getBandType());
            band.setStartMin(b.getStartMin());
            band.setEndMin(b.getEndMin());
            ruleBandMapper.insert(band);
        }
    }

    /**
     * 区间校验：类型限于峰值/平段/谷值；端点合法（左闭右开）；
     * 同版本内两两在环形时间轴上不得重叠（跨日区间按展开后判定）。
     */
    private void validateBands(List<RuleBandRequest> bands) {
        if (bands == null || bands.isEmpty()) {
            throw BusinessException.of("规则版本至少需要一个业务区间");
        }
        for (RuleBandRequest b : bands) {
            if (b.getBandType() == null || !BAND_TYPES.contains(b.getBandType())) {
                throw BusinessException.of("不支持的区间类型: " + b.getBandType()
                        + "（仅支持 PEAK/FLAT/VALLEY）");
            }
            try {
                BandWindows.checkRange(b.getStartMin(), b.getEndMin());
            } catch (IllegalArgumentException e) {
                throw BusinessException.of(e.getMessage());
            }
        }
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                RuleBandRequest a = bands.get(i);
                RuleBandRequest b = bands.get(j);
                if (BandWindows.overlaps(a.getStartMin(), a.getEndMin(),
                        b.getStartMin(), b.getEndMin())) {
                    throw BusinessException.of("规则区间重叠必须拒绝: "
                            + describe(a) + " 与 " + describe(b));
                }
            }
        }
    }

    private String describe(RuleBandRequest b) {
        return b.getBandType() + "[" + b.getStartMin() + "," + b.getEndMin() + ")";
    }
}
