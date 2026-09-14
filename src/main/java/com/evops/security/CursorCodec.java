package com.evops.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 游标（keyset 分页）编解码：内容为 "epochMilli|id"，Base64 URL 编码后对客户端不透明。
 * 排序键固定为 create_time DESC, id DESC，故游标是确定性主键补齐，
 * 不依赖易漂移的 OFFSET，插入新数据也不会翻页重复/跳行。
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(java.time.LocalDateTime time, Long id) {
        long epoch = time == null ? 0L
                : time.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        String raw = epoch + "|" + (id == null ? 0L : id);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回 [epochMilli, id]；非法令牌抛 IllegalArgumentException。 */
    public static Object[] decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep <= 0) {
                throw new IllegalArgumentException("bad cursor");
            }
            long epoch = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            java.time.LocalDateTime time = epoch == 0L ? null
                    : java.time.LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(epoch), java.time.ZoneOffset.UTC);
            return new Object[]{time, id};
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("分页游标不合法: " + cursor);
        }
    }
}
