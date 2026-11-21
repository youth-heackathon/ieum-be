package com.ieum;

import com.ieum.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}")
    String[] corsOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(corsOrigins).allowedMethods("*").allowCredentials(true);
    }

    /** /api/** 는 로그인한 보호자만. 새 엔드포인트도 기본으로 막힌다 (공개 경로는 여기 exclude에 추가). */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (!(handler instanceof HandlerMethod)) return true; // CORS preflight 등
                var session = req.getSession(false);
                if (session != null && session.getAttribute(AuthController.GUARDIAN) != null) return true;
                throw new ResponseStatusException(UNAUTHORIZED, "로그인이 필요해요");
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**", "/api/demo/sessions");
    }
}
