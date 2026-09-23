package com.jkxt.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.web.ApiErrorResponse;
import com.jkxt.security.auth.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<SessionAuthenticationFilter> sessionFilterRegistration(SessionAuthenticationFilter filter) {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
    registration.setEnabled(false); // Run once, inside the Spring Security chain only.
    return registration;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SessionAuthenticationFilter sessionAuthenticationFilter,
      ObjectMapper objectMapper,
      CorsConfigurationSource corsConfigurationSource
  ) throws Exception {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(sessionAuthenticationFilter, org.springframework.web.filter.CorsFilter.class)
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((request, response, exception) -> writeError(
                response,
                objectMapper,
                HttpServletResponse.SC_UNAUTHORIZED,
                "AUTH_UNAUTHENTICATED",
                "Authentication is required"
            ))
            .accessDeniedHandler((request, response, exception) -> writeError(
                response,
                objectMapper,
                HttpServletResponse.SC_FORBIDDEN,
                "AUTH_FORBIDDEN",
                "Permission is required"
            ))
        )
        // A1（P17）细粒度权限：按【能力(permission)】而非仅【角色(role)】鉴权。规则自上而下匹配，最具体在前。
        // 权限持有(V15 seed 后)：ADMIN=tenant:read+user/masterdata/exam/schedule:manage；
        // SUPER_ADMIN=platform/tenant/security/audit + 业务三 manage；VIEWER/TEACHER_QUERY=tenant:read。
        .authorizeHttpRequests(authorize -> authorize
            // 1) 公开端点。
            .requestMatchers(HttpMethod.POST,"/api/v1/demo/visitor","/api/v1/demo/sessions").permitAll()
            .requestMatchers(HttpMethod.POST,"/api/v1/demo/workspace/reset").hasRole("DEMO")
            .requestMatchers(HttpMethod.POST,"/api/v1/demo/tasks/*/simulate-publish").hasRole("DEMO")
            .requestMatchers(HttpMethod.GET,"/api/v1/demo/overview").hasRole("DEMO")
            .requestMatchers(
                "/api/v1/health",
                "/api/v1/version",
                "/api/v1/auth/captcha",
                "/api/v1/auth/login",
                "/api/v1/auth/school/login",
                "/api/v1/auth/platform/login",
                "/actuator/health/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html"
            ).permitAll()
            // 2) 平台运营台：跨租户，仅 SUPER_ADMIN。此粗门 backstop AdminPlatformController 的类级 @PreAuthorize，
            //    且必须排在下面按能力的规则之前，避免 /admin/** 被业务权限规则或读权限规则误判。
            .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
            // 3) 自助端点：任何登录用户对自己的资源可读写（含未来无 tenant:read 的角色）——不上 permission 门。
            .requestMatchers("/api/v1/auth/me", "/api/v1/auth/me/**").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
            .requestMatchers("/api/v1/notifications/**").authenticated()
            // 4) 业务写：按能力细分。ADMIN 与 SUPER_ADMIN(V15 后)均持有对应 manage 权限，故不误挡。
            //    4a 主数据 CRUD + 导入落库 → masterdata:manage
            .requestMatchers(HttpMethod.POST,
                "/api/v1/teachers/**", "/api/v1/classes/**", "/api/v1/classrooms/**", "/api/v1/subjects/**",
                "/api/v1/import/**").hasAuthority("masterdata:manage")
            .requestMatchers(HttpMethod.PUT,
                "/api/v1/teachers/**", "/api/v1/classes/**", "/api/v1/classrooms/**", "/api/v1/subjects/**")
                .hasAuthority("masterdata:manage")
            .requestMatchers(HttpMethod.DELETE,
                "/api/v1/teachers/**", "/api/v1/classes/**", "/api/v1/classrooms/**", "/api/v1/subjects/**")
                .hasAuthority("masterdata:manage")
            //    4b 排考编辑/发布/求解/AI 策略 → schedule:manage。schedule-jobs 在 /exam-tasks 下，必须排在
            //       通配的 /exam-tasks/** 之前，否则会被 4c 的 exam:manage 抢先匹配。
            .requestMatchers(HttpMethod.POST, "/api/v1/exam-tasks/*/schedule-jobs").hasAuthority("schedule:manage")
            .requestMatchers(HttpMethod.POST, "/api/v1/scheduling/**", "/api/v1/nl-policy/**")
                .hasAuthority("schedule:manage")
            .requestMatchers(HttpMethod.POST, "/api/v1/tasks/**").hasAuthority("schedule:manage")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/tasks/**").hasAuthority("schedule:manage")
            //    4c 考试任务创建/上传/导入校验 + 删除 → exam:manage
            .requestMatchers(HttpMethod.POST, "/api/v1/exam-tasks/**").hasAuthority("exam:manage")
            .requestMatchers(HttpMethod.DELETE, "/api/v1/exam-tasks/**").hasAuthority("exam:manage")
            // 5) 兜底：任何未被 4 覆盖的写 → 仍需 ADMIN/SUPER_ADMIN（防未来新增写端点漏配而放行）。
            .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            // 6) 读：需 tenant:read（V15 后四个角色都持有 → 不误挡；未来无此权限的角色则连读都被挡）。
            .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAuthority("tenant:read")
            // 7) 其余 /api/v1（非 GET/写的方法，如 HEAD/OPTIONS 已被 CORS 处理）→ 至少需登录。
            .requestMatchers("/api/v1/**").authenticated()
            .anyRequest().permitAll()
        );

    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${jkxt.cors.allowed-origins}") String allowedOrigins
  ) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowCredentials(true);
    Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .forEach(configuration::addAllowedOrigin);
    configuration.addAllowedHeader("*");
    configuration.addAllowedMethod("*");
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }

  private void writeError(
      HttpServletResponse response,
      ObjectMapper objectMapper,
      int status,
      String code,
      String message
  ) throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(code, message, status));
  }
}
