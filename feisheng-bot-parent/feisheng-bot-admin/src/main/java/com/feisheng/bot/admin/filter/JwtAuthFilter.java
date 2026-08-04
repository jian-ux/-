package com.feisheng.bot.admin.filter;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.admin.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, SysUserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, jakarta.servlet.FilterChain chain)
            throws jakarta.servlet.ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtUtil.validateToken(token)) {
                Claims claims = jwtUtil.parseToken(token);
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                SysUser user = userMapper.selectById(userId);
                if (user != null && Integer.valueOf(1).equals(user.getStatus())) {
                    Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
                    userMapper.selectRolesByUserId(userId).stream()
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
                    userMapper.selectPermissionsByUserId(userId).stream()
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
                    SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, username, authorities));
                }
            }
        }
        chain.doFilter(req, resp);
    }
}
