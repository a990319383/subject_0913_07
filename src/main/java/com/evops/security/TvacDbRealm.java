package com.evops.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.entity.TvacUser;
import com.evops.mapper.TvacUserMapper;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.LockedAccountException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

/**
 * 租户账号 Shiro 域：账号/角色/租户均来自 t_tvac_user。
 * 平台账号 bootstrap 仍由 SimpleAccountRealm 承载，两域并列，互不影响。
 * 演示环境口令以明文存储（与 bootstrap/bootstrap 一致），生产应替换为加盐哈希。
 */
public class TvacDbRealm extends AuthorizingRealm {

    private final TvacUserMapper userMapper;

    public TvacDbRealm(TvacUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        String username = String.valueOf(principals.getPrimaryPrincipal());
        TvacUser user = findUser(username);
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (user != null) {
            info.addRole(user.getRole());
        }
        return info;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token)
            throws AuthenticationException {
        String username = String.valueOf(token.getPrincipal());
        TvacUser user = findUser(username);
        if (user == null) {
            throw new UnknownAccountException("账号不存在: " + username);
        }
        if (!DataPermissionService.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new LockedAccountException("账号已停用: " + username);
        }
        return new SimpleAuthenticationInfo(user.getUsername(), user.getPassword(), getName());
    }

    private TvacUser findUser(String username) {
        return userMapper.selectOne(new QueryWrapper<TvacUser>().eq("username", username));
    }
}
