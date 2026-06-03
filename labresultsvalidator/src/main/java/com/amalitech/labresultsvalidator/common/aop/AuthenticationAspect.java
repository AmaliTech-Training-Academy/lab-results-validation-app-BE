package com.amalitech.labresultsvalidator.common.aop;

import com.amalitech.labresultsvalidator.domain.auth.dto.LoginRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class AuthenticationAspect {

    @Around("execution(* com.amalitech.labresultsvalidator.domain.auth.service.AuthService.login(..))")
    public Object interceptLogin(ProceedingJoinPoint joinPoint) throws Throwable {
        String email = extractEmail(joinPoint.getArgs());

        log.info("Login attempt: email={}", email);

        try {
            Object result = joinPoint.proceed();
            log.info("Login success: email={}", email);
            return result;
        } catch (BadCredentialsException ex) {
            log.warn("Login failed (bad credentials): email={}", email);
            throw ex;
        } catch (DisabledException ex) {
            log.warn("Login failed (account disabled): email={}", email);
            throw ex;
        } catch (Exception ex) {
            log.error("Login error: email={}, reason={}", email, ex.getMessage());
            throw ex;
        }
    }

    private String extractEmail(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof LoginRequest request) {
                return request.getEmail();
            }
        }
        return "unknown";
    }
}
