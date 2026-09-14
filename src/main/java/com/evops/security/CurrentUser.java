package com.evops.security;

import lombok.Getter;

/**
 * 一次请求内调用方的数据权限快照：
 * <ul>
 *   <li>system=true：平台系统账号（bootstrap），跨租户可见；</li>
 *   <li>tenantAdmin=true：租户管理员，可见本租户全部试验件；</li>
 *   <li>其余为租户普通账号（TENANT_VIEWER），仅可见显式授权的试验件。</li>
 * </ul>
 * 每个运营检索/聚合查询都必须携带该对象，数据权限条件在 SQL 层强制下推。
 */
@Getter
public class CurrentUser {
    private final String username;
    private final boolean system;
    private final Long userId;
    private final Long tenantId;
    private final boolean tenantAdmin;

    private CurrentUser(String username, boolean system, Long userId,
                        Long tenantId, boolean tenantAdmin) {
        this.username = username;
        this.system = system;
        this.userId = userId;
        this.tenantId = tenantId;
        this.tenantAdmin = tenantAdmin;
    }

    public static CurrentUser system(String username) {
        return new CurrentUser(username, true, null, null, true);
    }

    public static CurrentUser tenantUser(String username, Long userId, Long tenantId,
                                         boolean tenantAdmin) {
        return new CurrentUser(username, false, userId, tenantId, tenantAdmin);
    }
}
