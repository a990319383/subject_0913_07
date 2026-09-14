package com.evops.util;

import com.evops.common.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 RFC-4180 风格 CSV 解析器：支持双引号包裹、引号内逗号/换行/转义引号（""）、
 * CRLF 与 LF。不引入第三方依赖；解析时保留每个数据行的物理行号，
 * 以便错误明细回溯到文件原始行号。
 */
public final class CsvReader {

    private CsvReader() {
    }

    public static class CsvDocument {
        private final String[] header;
        private final List<CsvRow> rows;

        CsvDocument(String[] header, List<CsvRow> rows) {
            this.header = header;
            this.rows = rows;
        }

        public String[] getHeader() {
            return header;
        }

        public List<CsvRow> getRows() {
            return rows;
        }
    }

    public static class CsvRow {
        /** 文件中的物理行号（1 起，表头为第 1 行；引号跨行时取记录起始行） */
        private final int lineNo;
        private final String[] fields;

        CsvRow(int lineNo, String[] fields) {
            this.lineNo = lineNo;
            this.fields = fields;
        }

        public int getLineNo() {
            return lineNo;
        }

        public String[] getFields() {
            return fields;
        }
    }

    /** 解析整份 CSV，第一行必须是表头 */
    public static CsvDocument parse(String content) {
        if (content == null) {
            throw BusinessException.of("CSV 内容为空");
        }
        // 去掉 UTF-8 BOM
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        List<String[]> records = new ArrayList<>();
        List<Integer> lineNos = new ArrayList<>();
        parseRecords(content, records, lineNos);
        if (records.isEmpty()) {
            throw BusinessException.of("CSV 内容为空，缺少表头");
        }
        String[] header = records.get(0);
        List<CsvRow> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            String[] fields = records.get(i);
            // 纯空行跳过（不计入总数据行）
            if (fields.length == 1 && fields[0].trim().isEmpty()) {
                continue;
            }
            rows.add(new CsvRow(lineNos.get(i), fields));
        }
        return new CsvDocument(header, rows);
    }

    /** 解析分片报文：无表头，每行一条数据记录，行号由调用方给定基准 */
    public static List<CsvRow> parseShardPayload(String payload, int baseLineNo) {
        List<String[]> records = new ArrayList<>();
        List<Integer> lineNos = new ArrayList<>();
        parseRecords(payload, records, lineNos);
        List<CsvRow> rows = new ArrayList<>();
        int idx = 0;
        for (String[] fields : records) {
            int lineNo = baseLineNo + lineNos.get(idx) - 1;
            idx++;
            if (fields.length == 1 && fields[0].trim().isEmpty()) {
                continue;
            }
            rows.add(new CsvRow(lineNo, fields));
        }
        return rows;
    }

    /** 将一行字段序列化为 CSV 片段（用于分片报文落库后重试） */
    public static String toLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = fields[i] == null ? "" : fields[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void parseRecords(String content, List<String[]> records,
                                    List<Integer> lineNos) {
        int n = content.length();
        int i = 0;
        int lineNo = 1;
        while (i < n) {
            int recordStartLine = lineNo;
            List<String> fields = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            boolean fieldHasContent = false;
            boolean recordHasData = false;
            while (true) {
                if (i >= n) {
                    fields.add(cur.toString());
                    if (recordHasData) {
                        records.add(fields.toArray(new String[0]));
                        lineNos.add(recordStartLine);
                    }
                    return;
                }
                char c = content.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < n && content.charAt(i + 1) == '"') {
                            cur.append('"');
                            i += 2;
                        } else {
                            quoted = false;
                            i++;
                        }
                    } else {
                        cur.append(c);
                        if (c == '\n') {
                            lineNo++;
                        }
                        i++;
                    }
                    continue;
                }
                if (c == '"' && !fieldHasContent) {
                    quoted = true;
                    fieldHasContent = true;
                    recordHasData = true;
                    i++;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                    fieldHasContent = false;
                    recordHasData = true;
                    i++;
                } else if (c == '\n') {
                    fields.add(cur.toString());
                    lineNo++;
                    i++;
                    if (recordHasData) {
                        records.add(fields.toArray(new String[0]));
                        lineNos.add(recordStartLine);
                    }
                    break;
                } else if (c == '\r') {
                    // 单独 CR（旧 Mac）或 CRLF 的一部分
                    if (i + 1 < n && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    fields.add(cur.toString());
                    lineNo++;
                    i++;
                    if (recordHasData) {
                        records.add(fields.toArray(new String[0]));
                        lineNos.add(recordStartLine);
                    }
                    break;
                } else {
                    cur.append(c);
                    fieldHasContent = true;
                    recordHasData = true;
                    i++;
                }
            }
        }
    }
}
