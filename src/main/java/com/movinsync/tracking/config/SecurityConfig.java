package com.movinsync.tracking.config;

import com.movinsync.tracking.security.DeviceApiKeyFilter;
import com.movinsync.tracking.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configures JWT + device API-key security.
 */
@Configuration
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
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/pings").authenticated() // Needs Device API Key
                .requestMatchers("/api/**").authenticated()    // Everything else under /api needs auth (JWT)
                .anyRequest().permitAll()                      // Actuator, swagger, etc.
            )
            .addFilterBefore(deviceApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
            
        return http.build();
    }
}
