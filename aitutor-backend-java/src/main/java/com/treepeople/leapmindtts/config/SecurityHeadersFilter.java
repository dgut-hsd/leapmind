package com.treepeople.leapmindtts.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 安全响应头过滤器。
 * <p>
 * 为所有 HTTP 响应添加标准安全头，防止点击劫持、MIME 类型嗅探、
 * 降级攻击等常见 Web 安全威胁。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            // 防止点击劫持（Clickjacking）
            http.setHeader("X-Frame-Options", "DENY");
            // 防止 MIME 类型嗅探
            http.setHeader("X-Content-Type-Options", "nosniff");
            // 强制 HTTPS（生产环境生效，开发环境 localhost 豁免）
            http.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            // XSS 保护（浏览器内置 XSS 过滤器）
            http.setHeader("X-XSS-Protection", "1; mode=block");
            // 内容安全策略（允许同源和内联样式/脚本）
            http.setHeader("Content-Security-Policy",
                    "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: blob: https:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self' ws: wss:; " +
                    "media-src 'self' blob:; " +
                    "object-src 'none'");
            // 禁用引用泄露到外部
            http.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            // 禁用缓存（API 响应）
            http.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            http.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
