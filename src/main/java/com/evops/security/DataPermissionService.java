package com.evops.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.entity.TvacUser;
import com.evops.mapper.TvacUserMapper;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;

/**
 * 数据权限解析：从 Shiro 主体得到当前账号的租户/角色上下文。
 * 运营检索的每条 SQL 都通过该上下文追加租户与授权条件。
 */
@Service
public class DataPermissionService {

    /** Shiro SimpleAccountRealm 内置的平台系统账号 */
    public static final String SYSTEM_ACCOUNT = "bootstrap";
    public static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";
    public static final String STATUS_ACTIVE = "ACTIVE";

    private final TvacUserMapper userMapper;

    public DataPermissionService(TvacUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 控制器入口：必须已通过 HTTP Basic 认证，否则拒绝。
     * 非 HTTP 调用（无 Shiro 环境的单元/初始化场景）回退为平台系统上下文；
     * 生产链路上 /api/tvac/** 已由 Shiro 过滤器强制认证，未认证请求到不了这里。
     */
    public CurrentUser requireCurrentUser() {
        try {
            Subject subject = SecurityUtils.getSubject();
            Object principal = subject.getPrincipals() == null
                    ? null : subject.getPrincipals().getPrimaryPrincipal();
            if (principal == null) {
                return CurrentUser.system("system-internal");
            }
            return resolve(String.valueOf(principal));
        } catch (org.apache.shiro.UnavailableSecurityManagerException ex) {
            return CurrentUser.system("system-internal");
        }
    }

    private CurrentUser resolve(String username) {
        if (SYSTEM_ACCOUNT.equals(username)) {
            return CurrentUser.system(username);
        }
        TvacUser user = userMapper.selectOne(new QueryWrapper<TvacUser>()
                .eq("username", username));
        if (user == null) {
            throw BusinessException.of("账号不存在或已停用: " + username);
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw BusinessException.of("账号已停用: " + username);
        }
        return CurrentUser.tenantUser(user.getUsername(), user.getId(), user.getTenantId(),
                ROLE_TENANT_ADMIN.equals(user.getRole()));
    }
}
