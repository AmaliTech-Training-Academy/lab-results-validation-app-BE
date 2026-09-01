package com.amalitech.labresultsvalidator.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    private static final String PREFIX = "refresh_token:";

    public void storeRefreshToken(String userId, String refreshToken) {
        redisTemplate.opsForValue().set(
                PREFIX + userId,
                refreshToken,
                refreshExpiration,
                TimeUnit.MILLISECONDS
        );
    }

    public boolean validateRefreshToken(String userId, String refreshToken) {
        String stored = redisTemplate.opsForValue().get(PREFIX + userId);
        if (stored == null || refreshToken == null) {
            return false;
        }
        // Constant-time comparison as a matter of course for secret comparisons — String.equals()
        // short-circuits on the first mismatched byte.
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                refreshToken.getBytes(StandardCharsets.UTF_8));
    }

    public void deleteRefreshToken(String userId) {
        redisTemplate.delete(PREFIX + userId);
    }
}
