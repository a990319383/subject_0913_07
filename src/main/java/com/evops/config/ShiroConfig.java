package com.evops.config;

import com.evops.mapper.TvacUserMapper;
import com.evops.security.TvacDbRealm;
import org.apache.shiro.mgt.SessionStorageEvaluator;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.web.mgt.DefaultWebSessionStorageEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShiroConfig {

    /**
     * API 采用 HTTP Basic 无状态认证：不把主体写入容器 HttpSession，
     * 既避免每次请求生成会话（容器在低熵环境下 /dev/random 阻塞），
     * 也让 /api/tvac/** 天然无会话、可水平扩展。
     */
    @Bean
    public SessionStorageEvaluator sessionStorageEvaluator() {
        DefaultWebSessionStorageEvaluator evaluator = new DefaultWebSessionStorageEvaluator();
        evaluator.setSessionStorageEnabled(false);
        return evaluator;
    }

    /**
     * 两个认证域并列（ModularRealmAuthenticator 的 AtLeastOneSuccessful 策略）：
     * SimpleAccountRealm 承载平台账号 bootstrap（system 角色，跨租户可见）；
     * TvacDbRealm 承载 t_tvac_user 中的多租户账号。
     */
    @Bean
    public Realm bootstrapRealm() {
        SimpleAccountRealm realm = new SimpleAccountRealm();
        realm.addAccount("bootstrap", "bootstrap", "system");
        return realm;
    }

    @Bean
    public Realm tvacDbRealm(TvacUserMapper userMapper) {
        return new TvacDbRealm(userMapper);
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/api/tvac/**", "authcBasic");
        chain.addPathDefinition("/error", "anon");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
