package com.jkxt.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.web.ApiErrorResponse;
import com.jkxt.security.auth.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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
