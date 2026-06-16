package com.amalitech.labresultsvalidator.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetTokenServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private PasswordResetTokenService tokenService;

    // ── createToken ───────────────────────────────────────────────────────────

    @Test
    void createToken_returnsNonBlankUuidString() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String token = tokenService.createToken("instructor@amalitech.com");

        assertThat(token).isNotBlank();
        assertThat(token).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    @Test
    void createToken_storesEmailInRedisWithCorrectKeyAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String token = tokenService.createToken("instructor@amalitech.com");

        verify(valueOps).set(
                eq("password_reset:" + token),
                eq("instructor@amalitech.com"),
                eq(15L),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void createToken_eachCallProducesDifferentToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String token1 = tokenService.createToken("a@amalitech.com");
        String token2 = tokenService.createToken("a@amalitech.com");

        assertThat(token1).isNotEqualTo(token2);
    }

    // ── getEmailForToken ──────────────────────────────────────────────────────

    @Test
    void getEmailForToken_whenTokenExists_returnsEmail() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("password_reset:valid-token"))
                .thenReturn("instructor@amalitech.com");

        String email = tokenService.getEmailForToken("valid-token");

        assertThat(email).isEqualTo("instructor@amalitech.com");
    }

    @Test
    void getEmailForToken_whenTokenExpiredOrMissing_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("password_reset:expired-token")).thenReturn(null);

        String email = tokenService.getEmailForToken("expired-token");

        assertThat(email).isNull();
    }

    @Test
    void getEmailForToken_usesCorrectRedisKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        tokenService.getEmailForToken("some-token");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("password_reset:some-token");
    }

    // ── deleteToken ───────────────────────────────────────────────────────────

    @Test
    void deleteToken_deletesCorrectRedisKey() {
        tokenService.deleteToken("used-token");

        verify(redisTemplate).delete("password_reset:used-token");
    }
}
