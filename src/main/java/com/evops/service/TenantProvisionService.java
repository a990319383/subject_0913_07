package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacTenant;
import com.evops.entity.TvacUser;
import com.evops.entity.TvacUserGrant;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacTenantMapper;
import com.evops.mapper.TvacUserGrantMapper;
import com.evops.mapper.TvacUserMapper;
import com.evops.security.CurrentUser;
import com.evops.security.DataPermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 租户/账号/对象授权的开通服务。
 * 租户与账号的创建仅平台系统账号（bootstrap，system 角色）可执行；
 * 对象授权仅限 TENANT_ADMIN 对本租户试验件操作，VIEWER 只能看到被授权对象。
 */
@Service
public class TenantProvisionService {

    public static final String ROLE_TENANT_ADMIN = DataPermissionService.ROLE_TENANT_ADMIN;
    public static final String ROLE_TENANT_VIEWER = "TENANT_VIEWER";

    private final TvacTenantMapper tenantMapper;
    private final TvacUserMapper userMapper;
    private final TvacUserGrantMapper grantMapper;
    private final TvacArticleMapper articleMapper;
    private final DataPermissionService permissionService;

    public TenantProvisionService(TvacTenantMapper tenantMapper,
                                  TvacUserMapper userMapper,
                                  TvacUserGrantMapper grantMapper,
                                  TvacArticleMapper articleMapper,
                                  DataPermissionService permissionService) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.grantMapper = grantMapper;
        this.articleMapper = articleMapper;
        this.permissionService = permissionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TvacTenant createTenant(String code, String name) {
        return createTenantAs(permissionService.requireCurrentUser(), code, name);
    }

    /** 可注入身份的开通入口（初始化脚本/测试复用）。 */
    @Transactional(rollbackFor = Exception.class)
    public TvacTenant createTenantAs(CurrentUser cu, String code, String name) {
        if (!cu.isSystem()) {
            throw BusinessException.of("仅平台系统账号可开通租户");
        }
        TvacTenant tenant = new TvacTenant();
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setStatus("ACTIVE");
        tenantMapper.insert(tenant);
        return tenant;
    }

    @Transactional(rollbackFor = Exception.class)
    public TvacUser createUser(String username, String password, String realName,
                               Long tenantId, String role) {
        return createUserAs(permissionService.requireCurrentUser(),
                username, password, realName, tenantId, role);
    }

    /** 可注入身份的开通入口（初始化脚本/测试复用）。 */
    @Transactional(rollbackFor = Exception.class)
    public TvacUser createUserAs(CurrentUser cu, String username, String password, String realName,
                                 Long tenantId, String role) {
        if (!cu.isSystem()) {
            throw BusinessException.of("仅平台系统账号可开通账号");
        }
        if (!ROLE_TENANT_ADMIN.equals(role) && !ROLE_TENANT_VIEWER.equals(role)) {
            throw BusinessException.of("角色仅支持 TENANT_ADMIN / TENANT_VIEWER");
        }
        if (tenantMapper.selectById(tenantId) == null) {
            throw BusinessException.of("租户不存在: " + tenantId);
        }
        TvacUser user = new TvacUser();
        user.setUsername(username);
        user.setPassword(password);
        user.setRealName(realName);
        user.setTenantId(tenantId);
        user.setRole(role);
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        return user;
    }

    /** 给 VIEWER 账号授予试验件查看权（幂等：重复授权不新增）。 */
    @Transactional(rollbackFor = Exception.class)
    public TvacUserGrant grantArticle(Long userId, Long articleId) {
        return grantArticleAs(permissionService.requireCurrentUser(), userId, articleId);
    }

    /** 可注入调用方身份的授权入口（初始化脚本/压测复用）。 */
    @Transactional(rollbackFor = Exception.class)
    public TvacUserGrant grantArticleAs(CurrentUser cu, Long userId, Long articleId) {
        TvacUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of("账号不存在: " + userId);
        }
        TvacArticle article = articleMapper.selectById(articleId);
        if (article == null) {
            throw BusinessException.of("试验件不存在: " + articleId);
        }
        // 系统账号可跨租户授权；租户管理员只能授权本租户试验件
        if (!cu.isSystem() && (!cu.isTenantAdmin() || !user.getTenantId().equals(cu.getTenantId())
                || !user.getTenantId().equals(article.getTenantId()))) {
            throw BusinessException.of("无权为该账号/试验件授权（跨租户或角色不足）");
        }
        TvacUserGrant existing = grantMapper.selectOne(new QueryWrapper<TvacUserGrant>()
                .eq("user_id", userId)
                .eq("object_type", "ARTICLE")
                .eq("article_id", articleId));
        if (existing != null) {
            return existing;
        }
        TvacUserGrant grant = new TvacUserGrant();
        grant.setUserId(userId);
        grant.setObjectType("ARTICLE");
        grant.setArticleId(articleId);
        grantMapper.insert(grant);
        return grant;
    }

    public List<TvacUserGrant> listGrants(Long userId) {
        return grantMapper.selectList(
                new QueryWrapper<TvacUserGrant>().eq("user_id", userId).orderByAsc("id"));
    }
}
