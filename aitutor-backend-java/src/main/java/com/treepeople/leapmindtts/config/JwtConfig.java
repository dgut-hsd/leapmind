package com.treepeople.leapmindtts.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置类。
 * <p>
 * 管理 JWT 相关的配置参数。生产环境 <b>必须</b> 通过 {@code jwt.secret} 覆盖默认密钥。
 * </p>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /**
     * 默认密钥（仅用于开发环境）。
     * <p>
     * 生产环境必须通过 {@code application-prod.yml} 或环境变量 {@code JWT_SECRET} 覆盖。
     * 密钥长度需 >= 64 字节（HS512 要求）。
     * </p>
     */
    private String secret = "leapmind-tts-secret-key-for-jwt-token-generation-and-validation-secure";

    /** JWT 过期时间（毫秒），默认 24 小时。 */
    private Long expiration = 24 * 60 * 60 * 1000L;

    /** JWT Token 前缀。 */
    private String tokenPrefix = "Bearer ";

    /** JWT Header 名称。 */
    private String headerName = "Authorization";

    /**
     * 启动时校验密钥安全性。
     * <p>
     * 若密钥仍为默认值，打印严重警告日志，提醒运维人员覆盖配置。
     * </p>
     */
    @PostConstruct
    public void validateSecret() {
        String defaultSecret = "leapmind-tts-secret-key-for-jwt-token-generation-and-validation-secure";
        if (defaultSecret.equals(secret)) {
            log.error("========================================");
            log.error("SECURITY WARNING: JWT 密钥仍为默认值！");
            log.error("生产环境必须通过 jwt.secret 或 JWT_SECRET 环境变量覆盖。");
            log.error("当前密钥可被攻击者利用伪造任意用户 Token。");
            log.error("========================================");
        }
        if (secret == null || secret.getBytes().length < 64) {
            log.warn("JWT 密钥长度不足 64 字节，HS512 签名将使用随机密钥（多实例不互通）。");
        }
    }
}