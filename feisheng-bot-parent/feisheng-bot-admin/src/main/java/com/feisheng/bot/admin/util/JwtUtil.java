package com.feisheng.bot.admin.util;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expiration;
    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration:86400000}") long expiration) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be configured with at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }
    public String generateToken(Long userId, String username) {
        return Jwts.builder().subject(userId.toString()).claim("username", username)
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key).compact();
    }
    public Claims parseToken(String token) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload(); }
    public boolean validateToken(String token) { try { parseToken(token); return true; } catch (Exception e) { return false; } }
}
