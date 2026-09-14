package com.evops.service;

import com.evops.dto.OperationSearchRequest;
import com.evops.dto.TmStatsQuery;
import com.evops.mapper.OperationQueryMapper;
import com.evops.security.CurrentUser;
import com.evops.security.CursorCodec;
import com.evops.security.DataPermissionService;
import com.evops.vo.ArticleFrameStatsVo;
import com.evops.vo.OperationSearchItemVo;
import com.evops.vo.PageResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 航天器热真空试验与遥测判读运营检索。
 *
 * 关键约束（题面硬性要求，逐条在实现中保证）：
 * 1) 至少 4 个条件 AND/范围组合：编号/名称/型号/批次/状态/建档日期区间/计划日期区间/
 *    温压曲线区间/循环次全部可同时 AND 叠加；
 * 2) 一对多关联不放大主表：计划、曲线条件一律 EXISTS 半连接；计数与分页基于主表；
 * 3) 稳定排序：create_time DESC, id DESC（确定性复合键，时间并列时由主键补齐）；
 * 4) 分页：pageSize 强制 1-100（DTO 校验 + 服务层兜底），同时支持页码与游标 keyset；
 * 5) 每次查询都应用租户与角色数据权限（CurrentUser 必传，SQL 内下推）。
 */
@Service
public class OperationQueryService {

    public static final String OBJECT_ARTICLE = "ARTICLE";
    public static final String OBJECT_PLAN = "PLAN";

    private final OperationQueryMapper queryMapper;
    private final DataPermissionService permissionService;

    public OperationQueryService(OperationQueryMapper queryMapper,
                                 DataPermissionService permissionService) {
        this.queryMapper = queryMapper;
        this.permissionService = permissionService;
    }

    /** 控制器入口：解析当前账号数据权限后执行检索。 */
    public PageResult<OperationSearchItemVo> search(OperationSearchRequest req) {
        return searchAs(permissionService.requireCurrentUser(), req);
    }

    /**
     * 可注入调用方身份的检索入口（供定时任务/压测脚本复用）。
     * 无论何种入口，数据权限条件都会进入 SQL。
     */
    public PageResult<OperationSearchItemVo> searchAs(CurrentUser cu, OperationSearchRequest req) {
        normalize(req);
        int limit = req.getPageSize();

        boolean useCursor = req.getCursor() != null && !req.getCursor().isEmpty();
        long offset = useCursor ? 0L : req.getOffset();

        if (OBJECT_PLAN.equalsIgnoreCase(req.getObjectType())) {
            long total = queryMapper.countPlans(cu, req);
            List<OperationSearchItemVo> rows = queryMapper.pagePlans(cu, req, limit + 1, offset);
            return buildPage(rows, total, req, limit, useCursor);
        }
        long total = queryMapper.countArticles(cu, req);
        List<OperationSearchItemVo> rows = queryMapper.pageArticles(cu, req, limit + 1, offset);
        return buildPage(rows, total, req, limit, useCursor);
    }

    private PageResult<OperationSearchItemVo> buildPage(List<OperationSearchItemVo> rows, long total,
                                                        OperationSearchRequest req, int limit,
                                                        boolean useCursor) {
        boolean hasNext = rows.size() > limit;
        if (hasNext) {
            rows = rows.subList(0, limit);
        }
        String nextCursor = null;
        if (hasNext && !rows.isEmpty()) {
            OperationSearchItemVo last = rows.get(rows.size() - 1);
            nextCursor = CursorCodec.encode(last.getCreateTime(), last.getId());
        }
        int pageNum = useCursor ? req.getPageNum() : req.getPageNum();
        return PageResult.of(rows, total, pageNum, limit, hasNext, nextCursor);
    }

    /** 遥测帧按试验件分区聚合（控制器入口，自带数据权限）。 */
    public PageResult<ArticleFrameStatsVo> frameStats(TmStatsQuery q) {
        return frameStatsAs(permissionService.requireCurrentUser(), q);
    }

    /** 遥测帧按试验件分区聚合（可注入身份）。 */
    public PageResult<ArticleFrameStatsVo> frameStatsAs(CurrentUser cu, TmStatsQuery q) {
        normalizeStats(q);
        long total = queryMapper.countFrameGroups(cu, q);
        List<ArticleFrameStatsVo> rows = queryMapper.pageFrameGroups(
                cu, q, q.getPageSize(), (long) (q.getPageNum() - 1) * q.getPageSize());
        return PageResult.of(rows, total, q.getPageNum(), q.getPageSize(),
                (long) q.getPageNum() * (long) q.getPageSize() < total, null);
    }

    private void normalize(OperationSearchRequest req) {
        if (req.getObjectType() == null || req.getObjectType().isEmpty()) {
            req.setObjectType(OBJECT_ARTICLE);
        }
        String type = req.getObjectType().toUpperCase();
        if (!OBJECT_ARTICLE.equals(type) && !OBJECT_PLAN.equals(type)) {
            throw new IllegalArgumentException("objectType 只支持 ARTICLE 或 PLAN");
        }
        req.setObjectType(type);
        if (req.getPageNum() == null || req.getPageNum() < 1) {
            req.setPageNum(1);
        }
        // 服务层兜底，防止绕过 Bean Validation 的内部调用
        Integer size = req.getPageSize();
        if (size == null || size < 1 || size > 100) {
            throw new IllegalArgumentException("pageSize 只能为 1-100");
        }
        if (req.getCreateTimeFrom() != null && req.getCreateTimeTo() != null
                && req.getCreateTimeFrom().isAfter(req.getCreateTimeTo())) {
            throw new IllegalArgumentException("建档日期起始不能晚于截止");
        }
        if (req.getPlanTimeFrom() != null && req.getPlanTimeTo() != null
                && req.getPlanTimeFrom().isAfter(req.getPlanTimeTo())) {
            throw new IllegalArgumentException("计划日期起始不能晚于截止");
        }
        if (req.getTempFromC() != null && req.getTempToC() != null
                && req.getTempFromC().compareTo(req.getTempToC()) > 0) {
            throw new IllegalArgumentException("温度下限不能高于上限");
        }
        if (req.getPressureFromPa() != null && req.getPressureToPa() != null
                && req.getPressureFromPa().compareTo(req.getPressureToPa()) > 0) {
            throw new IllegalArgumentException("压力下限不能高于上限");
        }
        if (req.getCursor() != null && !req.getCursor().isEmpty()) {
            Object[] pair = CursorCodec.decode(req.getCursor());
            req.setCursorTime((LocalDateTime) pair[0]);
            req.setCursorId((Long) pair[1]);
        }
    }

    private void normalizeStats(TmStatsQuery q) {
        if (q.getPageNum() == null || q.getPageNum() < 1) {
            q.setPageNum(1);
        }
        if (q.getPageSize() == null || q.getPageSize() < 1 || q.getPageSize() > 100) {
            throw new IllegalArgumentException("pageSize 只能为 1-100");
        }
        if (q.getCycleFrom() != null && q.getCycleTo() != null
                && q.getCycleFrom() > q.getCycleTo()) {
            throw new IllegalArgumentException("循环次起始不能大于截止");
        }
    }
}
