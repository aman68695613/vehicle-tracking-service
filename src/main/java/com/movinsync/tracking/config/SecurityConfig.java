package com.movinsync.tracking.config;

import com.movinsync.tracking.security.DeviceApiKeyFilter;
import com.movinsync.tracking.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configures JWT + device API-key security.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final DeviceApiKeyFilter deviceApiKeyFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter, DeviceApiKeyFilter deviceApiKeyFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.deviceApiKeyFilter = deviceApiKeyFilter;
    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, authException) -> {
                response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/pings").authenticated() // Needs Device API Key or JWT (Device check in controller)
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/metrics", "/actuator/prometheus").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()    // Everything else under /api needs auth (JWT)
                .anyRequest().permitAll()                      // swagger, etc.
            )
            .addFilterBefore(deviceApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
            
        return http.build();
    }
}
