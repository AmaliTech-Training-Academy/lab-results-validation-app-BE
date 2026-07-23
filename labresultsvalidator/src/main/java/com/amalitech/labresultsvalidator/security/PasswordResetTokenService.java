package com.amalitech.labresultsvalidator.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PasswordResetTokenService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String PREFIX = "password_reset:";
    private static final long TOKEN_TTL_MINUTES = 15;

    public String createToken(String email) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, email, TOKEN_TTL_MINUTES, TimeUnit.MINUTES);
        return token;
    }

    public String getEmailForToken(String token) {
        return redisTemplate.opsForValue().get(PREFIX + token);
    }

    public void deleteToken(String token) {
        redisTemplate.delete(PREFIX + token);
    }
}
