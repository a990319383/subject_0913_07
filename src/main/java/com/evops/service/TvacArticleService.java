package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.ArticleBatchCreateRequest;
import com.evops.dto.ArticleCreateRequest;
import com.evops.dto.ArticleUpdateRequest;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacPlan;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.vo.ArticleBatchVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TvacArticleService {

    private final TvacArticleMapper articleMapper;
    private final TvacPlanMapper planMapper;

    public TvacArticleService(TvacArticleMapper articleMapper, TvacPlanMapper planMapper) {
        this.articleMapper = articleMapper;
        this.planMapper = planMapper;
    }

    /** 单件建档 */
    public TvacArticle create(ArticleCreateRequest req) {
        TvacArticle article = new TvacArticle();
        article.setArticleCode(req.getArticleCode());
        article.setArticleName(req.getArticleName());
        article.setTargetModel(req.getTargetModel());
        article.setBatchNo(req.getBatchNo());
        article.setStatus(TvacConst.ArticleStatus.REGISTERED);
        article.setRemark(req.getRemark());
        articleMapper.insert(article);
        return article;
    }

    /** 批次建档：同一批次号下一次登记多件，件内编号重复直接拒绝，整批回滚 */
    @Transactional(rollbackFor = Exception.class)
    public List<TvacArticle> createBatch(ArticleBatchCreateRequest req) {
        Set<String> codesInBatch = new HashSet<>();
        for (ArticleBatchCreateRequest.Item item : req.getArticles()) {
            if (!codesInBatch.add(item.getArticleCode())) {
                throw BusinessException.of("批次内试验件编号重复: " + item.getArticleCode());
            }
        }
        List<TvacArticle> saved = new java.util.ArrayList<>();
        for (ArticleBatchCreateRequest.Item item : req.getArticles()) {
            TvacArticle article = new TvacArticle();
            article.setArticleCode(item.getArticleCode());
            article.setArticleName(item.getArticleName());
            article.setTargetModel(req.getTargetModel());
            article.setBatchNo(req.getBatchNo());
            article.setStatus(TvacConst.ArticleStatus.REGISTERED);
            article.setRemark(item.getRemark());
            articleMapper.insert(article);
            saved.add(article);
        }
        return saved;
    }

    public TvacArticle getById(Long id) {
        TvacArticle article = articleMapper.selectById(id);
        if (article == null) {
            throw BusinessException.of("试验件不存在: " + id);
        }
        return article;
    }

    public TvacArticle getByCode(String code) {
        TvacArticle article = articleMapper.selectOne(
                new QueryWrapper<TvacArticle>().eq("article_code", code));
        if (article == null) {
            throw BusinessException.of("试验件不存在: " + code);
        }
        return article;
    }

    public List<TvacArticle> list(String batchNo, String status) {
        QueryWrapper<TvacArticle> qw = new QueryWrapper<>();
        if (batchNo != null && !batchNo.isEmpty()) {
            qw.eq("batch_no", batchNo);
        }
        if (status != null && !status.isEmpty()) {
            qw.eq("status", status);
        }
        qw.orderByDesc("id");
        return articleMapper.selectList(qw);
    }

    public List<ArticleBatchVo> listBatches() {
        return articleMapper.listBatches();
    }

    public TvacArticle update(Long id, ArticleUpdateRequest req) {
        TvacArticle article = getById(id);
        if (req.getArticleName() != null) {
            article.setArticleName(req.getArticleName());
        }
        if (req.getTargetModel() != null) {
            article.setTargetModel(req.getTargetModel());
        }
        if (req.getRemark() != null) {
            article.setRemark(req.getRemark());
        }
        articleMapper.updateById(article);
        return article;
    }

    /**
     * 试验件状态流转：
     * REGISTERED -> IN_TEST -> COMPLETED；任意活跃状态可 SCRAPPED，报废不可逆。
     */
    public TvacArticle changeStatus(Long id, String target) {
        TvacArticle article = getById(id);
        String current = article.getStatus();
        if (current.equals(target)) {
            throw BusinessException.of("试验件已处于目标状态: " + target);
        }
        if (!allowedTransition(current, target)) {
            throw BusinessException.of(
                    "试验件状态不允许从 " + current + " 流转到 " + target);
        }
        article.setStatus(target);
        articleMapper.updateById(article);
        return article;
    }

    private boolean allowedTransition(String from, String to) {
        Set<String> allowed;
        switch (from) {
            case TvacConst.ArticleStatus.REGISTERED:
                allowed = new HashSet<>(Arrays.asList(
                        TvacConst.ArticleStatus.IN_TEST, TvacConst.ArticleStatus.SCRAPPED));
                break;
            case TvacConst.ArticleStatus.IN_TEST:
                allowed = new HashSet<>(Arrays.asList(
                        TvacConst.ArticleStatus.COMPLETED, TvacConst.ArticleStatus.SCRAPPED));
                break;
            case TvacConst.ArticleStatus.COMPLETED:
            case TvacConst.ArticleStatus.SCRAPPED:
            default:
                allowed = new HashSet<>();
        }
        return allowed.contains(to);
    }

    /**
     * 删除保护：已存在试验计划（试验已进入过排程）的试验件不允许直接删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TvacArticle article = getById(id);
        Long planCount = planMapper.selectCount(
                new QueryWrapper<TvacPlan>().eq("article_id", id));
        if (planCount != null && planCount > 0) {
            throw BusinessException.of("试验件已关联 " + planCount
                    + " 个试验计划，不能直接删除，请先处理关联计划");
        }
        articleMapper.deleteById(article.getId());
    }
}
