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
                    "/api/public/conversation-images/**",
                    "/gateway/channel/dingtalk/**", "/gateway/channel/wechat/**"
                ).permitAll()
                .requestMatchers("/api/admin/user/info").authenticated()
                .requestMatchers("/api/admin/permission/**", "/api/admin/role/**")
                    .hasAuthority("system:permission:assign")
                .requestMatchers("/api/admin/user/**").hasAuthority("system:user:list")
                .requestMatchers("/api/admin/statistics/**").hasAuthority("dashboard:view")
                .requestMatchers("/api/admin/channel/config/**").hasAuthority("channel:view")
                .requestMatchers("/api/admin/customer/**").hasAuthority("customer:view")
                .requestMatchers("/api/admin/intent/**").hasAuthority("intent:view")
                .requestMatchers("/api/admin/ai/model/enabled")
                    .hasAnyAuthority("ai:model:view", "playground:view")
                .requestMatchers("/api/admin/ai/model/**").hasAuthority("ai:model:view")
                .requestMatchers("/api/admin/ticket/**")
                    .hasAnyAuthority("ticket:view", "conversation:view")
                .requestMatchers("/api/admin/conversation/**").hasAuthority("conversation:view")
                .requestMatchers("/api/admin/playground/**")
                    .hasAnyAuthority("playground:view", "conversation:view")
                .requestMatchers("/api/admin/log/**").hasAuthority("log:view")
                .requestMatchers("/api/admin/rules/**").hasAuthority("settings:rules:view")
                .requestMatchers("/api/admin/reply-strategy/**")
                    .hasAuthority("settings:reply-strategy:view")
                .requestMatchers("/api/admin/knowledge/item/**")
                    .hasAuthority("knowledge:faq:list")
                .requestMatchers("/api/admin/doc/**")
                    .hasAnyAuthority("knowledge:upload:view", "knowledge:semantic:view")
                .requestMatchers("/api/admin/knowledge/semantic-unit/**")
                    .hasAuthority("knowledge:semantic:view")
                .requestMatchers("/api/admin/knowledge/migrations/*/conflicts/*/resolve",
                                 "/api/admin/knowledge/migrations/*/review/confirm")
                    .hasAuthority("knowledge:migration:review")
                .requestMatchers("/api/admin/knowledge/migrations/*/switch")
                    .hasAuthority("knowledge:migration:switch")
                .requestMatchers("/api/admin/knowledge/sets/*/rollback")
                    .hasAuthority("knowledge:migration:rollback")
                .requestMatchers("/api/admin/knowledge/migrations/**")
                    .hasAuthority("knowledge:migration:view")
                .requestMatchers("/api/admin/knowledge-quality/**")
                    .hasAuthority("knowledge:quality:view")
                .requestMatchers("/api/admin/unmatched/**")
                    .hasAuthority("knowledge:unmatched:view")
                .requestMatchers("/api/admin/rag/**", "/api/admin/business/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
