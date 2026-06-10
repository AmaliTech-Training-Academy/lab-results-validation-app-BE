package com.amalitech.labresultsvalidator.common.aop;

import com.amalitech.labresultsvalidator.common.aop.annotation.Auditable;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(4)
@Slf4j
public class AuditLoggingAspect {

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        String user = currentUser();
        String action = auditable.action();
        String resource = auditable.resource();
        String requestId = MDC.get("requestId");

        log.info("AUDIT action={} resource={} user={} requestId={} status=INITIATED",
                action, resource, user, requestId);
        try {
            Object result = joinPoint.proceed();
            log.info("AUDIT action={} resource={} user={} requestId={} status=SUCCESS",
                    action, resource, user, requestId);
            return result;
        } catch (Exception ex) {
            log.error("AUDIT action={} resource={} user={} requestId={} status=FAILED reason={}",
                    action, resource, user, requestId, ex.getMessage());
            throw ex;
        }
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }
}
