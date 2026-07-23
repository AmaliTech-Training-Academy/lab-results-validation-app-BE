package com.amalitech.labresultsvalidator.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

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
        return stored != null && stored.equals(refreshToken);
    }

    public void deleteRefreshToken(String userId) {
        redisTemplate.delete(PREFIX + userId);
    }
}
