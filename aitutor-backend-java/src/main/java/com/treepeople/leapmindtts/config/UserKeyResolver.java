package com.treepeople.leapmindtts.config;

import io.github.resilience4j.ratelimiter.KeyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("userKeyResolver")
public class UserKeyResolver implements KeyResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        // 1. Try to resolve from Spring Security context
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }

        // 2. Fallback to request parameter
        String userId = request.getParameter("userId");
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }

        // 3. Fallback to request header
        userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }

        // 4. Fallback to client IP address
        return request.getRemoteAddr();
    }
}
