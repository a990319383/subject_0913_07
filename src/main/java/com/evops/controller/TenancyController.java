package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.GrantRequest;
import com.evops.dto.TenantCreateRequest;
import com.evops.dto.TenantUserCreateRequest;
import com.evops.entity.TvacTenant;
import com.evops.entity.TvacUser;
import com.evops.entity.TvacUserGrant;
import com.evops.service.TenantProvisionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 多租户开通：租户/账号由平台系统账号（bootstrap）创建；
 * 对象级授权由系统账号或租户管理员执行。所有接口同样走 authcBasic。
 */
@RestController
@RequestMapping("/api/tvac/tenancy")
public class TenancyController {

    private final TenantProvisionService provisionService;

    public TenancyController(TenantProvisionService provisionService) {
        this.provisionService = provisionService;
    }

    @PostMapping("/tenants")
    public ApiResponse<TvacTenant> createTenant(@Valid @RequestBody TenantCreateRequest req) {
        return ApiResponse.ok(provisionService.createTenant(
                req.getTenantCode(), req.getTenantName()));
    }

    @PostMapping("/users")
    public ApiResponse<TvacUser> createUser(@Valid @RequestBody TenantUserCreateRequest req) {
        return ApiResponse.ok(provisionService.createUser(req.getUsername(), req.getPassword(),
                req.getRealName(), req.getTenantId(), req.getRole()));
    }

    /** 给普通租户账号授予某个试验件的查看权（幂等） */
    @PostMapping("/grants")
    public ApiResponse<TvacUserGrant> grant(@Valid @RequestBody GrantRequest req) {
        return ApiResponse.ok(provisionService.grantArticle(req.getUserId(), req.getArticleId()));
    }

    @GetMapping("/users/{userId}/grants")
    public ApiResponse<List<TvacUserGrant>> listGrants(@PathVariable Long userId) {
        return ApiResponse.ok(provisionService.listGrants(userId));
    }
}
