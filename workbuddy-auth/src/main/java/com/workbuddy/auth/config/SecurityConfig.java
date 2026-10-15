package com.workbuddy.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置：无状态过滤链、登录注册放行、密码编码器。
 *
 * <p>接口级角色控制走方法注解（{@code @AdminOnly} / {@code @PreAuthorize}），
 * 由 {@code @EnableMethodSecurity} 开启；这里只声明「谁免登录、其余一律需认证」。
 *
 * @author cxxl
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    private final RestAccessDeniedHandler restAccessDeniedHandler;

    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    /** 安全过滤链：登录/注册、接口文档与健康探针放行；其余请求需登录，角色由方法注解判定 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/auth/register").permitAll()
                // 头像读取免登录：<img src> 不会带 Authorization 头，写接口仍要求登录
                .requestMatchers(HttpMethod.GET, "/user/avatar/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 健康探针与指标端点免认证（本地排查用；对外暴露前请自行收紧）
                        .requestMatchers("/actuator/health", "/actuator/metrics").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        // 未认证 → 40101；已认证但无权限 → 40104；两者都归一成 R + HTTP 200
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 密码编码器（BCrypt） */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
