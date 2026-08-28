package com.amalitech.labresultsvalidator.common.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

@Component
public class CookieUtils {

    @Value("${jwt.refresh-cookie-name}")
    private String refreshCookieName;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    // Defaults to true (HTTPS-only) everywhere; only local HTTP dev needs COOKIE_SECURE=false,
    // since browsers silently drop a Secure cookie sent over plain HTTP.
    @Value("${app.cookie-secure:true}")
    private boolean cookieSecure;

    public void setRefreshTokenCookie(
            HttpServletResponse response,
            String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                // Strict is safe here: this cookie is only ever read by /api/v1/auth/{refresh,logout},
                // both called directly by our own SPA, never as the target of a cross-site navigation.
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ofMillis(refreshExpiration))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> refreshCookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
