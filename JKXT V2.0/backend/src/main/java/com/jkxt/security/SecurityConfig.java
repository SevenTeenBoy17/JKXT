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
        .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
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
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(
                "/api/v1/health",
                "/api/v1/version",
                "/api/v1/auth/captcha",
                "/api/v1/auth/login",
                "/actuator/health/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html"
            ).permitAll()
            // Platform-operator surface (cross-tenant tenant/account/security/backup/announcement
            // management) must require the SUPER_ADMIN role — a tenant ADMIN must NOT reach it.
            // This coarse matcher backstops the method-level @PreAuthorize on AdminPlatformController.
            .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
            // Self-service POSTs every authenticated user may call (sign out, mark onboarding done,
            // manage their own notification read-state).
            .requestMatchers(HttpMethod.POST,
                "/api/v1/auth/logout", "/api/v1/auth/me/onboarded", "/api/v1/notifications/**").authenticated()
            // All other business mutations (master-data create/update/delete, import commit, schedule
            // edit/publish, exam-task/scheduling, AI policy authoring) require ADMIN or SUPER_ADMIN.
            // Read-only roles (VIEWER / TEACHER_QUERY) keep GET access but are denied writes (P1-1):
            // previously every authenticated user could write because only /admin/** was role-gated.
            .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
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
