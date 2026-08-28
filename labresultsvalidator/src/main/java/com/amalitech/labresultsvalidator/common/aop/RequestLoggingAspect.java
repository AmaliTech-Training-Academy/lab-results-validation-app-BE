package com.amalitech.labresultsvalidator.common.aop;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Aspect
@Component
@Order(1)
@Slf4j
public class RequestLoggingAspect {

    private static final long SLOW_REQUEST_THRESHOLD_MS = 1_000;
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_USER = "user";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void restController() {}

    @Around("restController()")
    public Object logRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        String user = currentUser();
        HttpServletRequest httpReq = currentHttpRequest();
        String httpMethod = httpReq != null ? httpReq.getMethod() : "-";
        String uri = httpReq != null ? httpReq.getRequestURI() : "-";

        // Honor an inbound correlation id (set by the frontend or a load balancer) instead of
        // always minting our own, so a request can be traced end-to-end across hops. Echoed back
        // on the response header below regardless of success/failure — including from
        // GlobalExceptionHandler's error responses, which this aspect's MDC scope doesn't reach
        // since @RestControllerAdvice runs outside this pointcut — so a support request citing "I
        // got a 500" has something to grep in the logs even on the error path.
        String inboundId = httpReq != null ? httpReq.getHeader(REQUEST_ID_HEADER) : null;
        String requestId = (inboundId != null && !inboundId.isBlank())
            ? inboundId
            : UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        HttpServletResponse httpResp = currentHttpResponse();
        if (httpResp != null) {
            httpResp.setHeader(REQUEST_ID_HEADER, requestId);
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_USER, user);

        log.info("REQ  method={} uri={} user={}", httpMethod, uri, user);

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            int status = resolveHttpStatus(result);

            if (elapsed > SLOW_REQUEST_THRESHOLD_MS) {
                log.warn("RESP method={} uri={} status={} elapsed={}ms [SLOW]",
                        httpMethod, uri, status, elapsed);
            } else {
                log.info("RESP method={} uri={} status={} elapsed={}ms",
                        httpMethod, uri, status, elapsed);
            }
            return result;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("ERR  method={} uri={} elapsed={}ms error={}",
                    httpMethod, uri, elapsed, ex.getMessage());
            throw ex;
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER);
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

    private HttpServletRequest currentHttpRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private HttpServletResponse currentHttpResponse() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getResponse() : null;
    }

    private int resolveHttpStatus(Object result) {
        if (result instanceof ResponseEntity<?> re) {
            return re.getStatusCode().value();
        }
        return 200;
    }
}
