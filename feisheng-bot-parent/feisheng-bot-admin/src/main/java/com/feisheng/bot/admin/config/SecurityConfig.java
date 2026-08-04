package com.feisheng.bot.admin.config;
import com.feisheng.bot.admin.filter.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;
    public SecurityConfig(JwtAuthFilter jwtAuthFilter) { this.jwtAuthFilter = jwtAuthFilter; }
    @Bean public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((request, response, ex) ->
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)))
            .authorizeHttpRequests(a -> a
                .requestMatchers(
                    "/api/admin/login", "/api/health",
                    "/api/public/knowledge-images/**",
                    "/gateway/channel/dingtalk/**", "/gateway/channel/wechat/**"
                ).permitAll()
                .requestMatchers(
                    "/api/admin/user/**",
                    "/api/admin/role/**",
                    "/api/admin/permission/**",
                    "/api/admin/ai/model/**",
                    "/api/admin/rag/**",
                    "/api/admin/knowledge/semantic-unit/**",
                    "/api/admin/business/**",
                    "/api/admin/channel/config/**",
                    "/api/admin/rules/**"
                ).hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
