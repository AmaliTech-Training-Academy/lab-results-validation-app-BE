package com.amalitech.labresultsvalidator.common.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class ServiceLoggingAspect {

    @Around("execution(* com.amalitech.labresultsvalidator.domain..service.*.*(..))")
    public Object logServiceCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        String user = resolveCurrentUser();
        long start = System.currentTimeMillis();

        log.debug("[{}] --> {}", user, method);

        try {
            Object result = joinPoint.proceed();
            log.debug("[{}] <-- {} ({}ms)", user, method, System.currentTimeMillis() - start);
            return result;
        } catch (Exception ex) {
            log.error("[{}] <!- {} failed after {}ms: {}", user, method,
                    System.currentTimeMillis() - start, ex.getMessage());
            throw ex;
        }
    }

    private String resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }
}
