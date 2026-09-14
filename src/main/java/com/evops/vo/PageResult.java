package com.evops.vo;

import lombok.Data;

import java.util.List;

/**
 * 运营检索分页结果。
 * total 为去重后的主记录总数（关联表一对多不参与计数）；
 * 排序在全量匹配集上确定：create_time DESC, id DESC；
 * hasNext/nextCursor 支持游标（keyset）翻页，pageNum/pageSize 支持页码翻页。
 */
@Data
public class PageResult<T> {
    private List<T> records;
    private long total;
    private int pageNum;
    private int pageSize;
    private boolean hasNext;
    private String nextCursor;

    public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize,
                                       boolean hasNext, String nextCursor) {
        PageResult<T> r = new PageResult<>();
        r.records = records;
        r.total = total;
        r.pageNum = pageNum;
        r.pageSize = pageSize;
        r.hasNext = hasNext;
        r.nextCursor = nextCursor;
        return r;
    }
}
