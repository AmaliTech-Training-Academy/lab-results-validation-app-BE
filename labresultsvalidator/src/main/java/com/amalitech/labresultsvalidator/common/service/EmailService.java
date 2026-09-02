package com.amalitech.labresultsvalidator.common.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All public methods MUST carry {@code @Async("emailTaskExecutor")} so that no caller
 * ever blocks a request thread waiting for SMTP. Methods triggered from inside a
 * {@code @Transactional} boundary also carry {@code @TransactionalEventListener(AFTER_COMMIT)}
 * so the email fires only after the database change is durable — never on rollback.
 *
 * <p>To add a new email type:
 * <ul>
 *   <li>Transactional caller: create an event record, publish it via
 *       {@code ApplicationEventPublisher}, and add a {@code @Async + @TransactionalEventListener}
 *       handler here that delegates to {@link #dispatch}.</li>
 *   <li>Non-transactional caller: add a {@code @Async("emailTaskExecutor")} public method here
 *       and call it directly.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private volatile String template;

    @Async("emailTaskExecutor")
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        LOG.info("[email] password-reset email queued to={}", toEmail);
        dispatch(
            toEmail,
            "AmalitechTraining — Password Reset Request",
            buildHtmlEmail(
                buildPasswordResetContent(),
                resetLink,
                "Reset My Password"
            )
        );
    }

    /**
     * Sends an arbitrary pre-rendered subject/body — used for notification digests, whose content
     * is built and stored at staging time, not here. Synchronous, for callers that need immediate
     * success/failure feedback, such as an admin's manual "send now"/"retry" action, where
     * fire-and-forget would hide the result from the request that triggered it.
     */
    public void sendPlainEmailSync(String toEmail, String subject, String htmlContent) {
        LOG.info("[email] sync plain email queued to={} subject={}", toEmail, subject);
        dispatch(toEmail, subject, buildHtmlEmail(htmlContent, frontendUrl, "Open Validata"));
    }

    // ── Shared internal dispatcher ────────────────────────────────────────────

    private static final Map<String, String> INLINE_IMAGES = new LinkedHashMap<>();

    static {
        INLINE_IMAGES.put("logo-header", "static/images/email/logo-header.png");
        INLINE_IMAGES.put("header-art",  "static/images/email/header-art.png");
        INLINE_IMAGES.put("logo-dark",   "static/images/email/logo-dark.png");
    }

    private void dispatch(String to, String subject, String htmlBody) {
        long start = System.currentTimeMillis();
        LOG.debug("[email] dispatch starting to={} subject={} from={}", to, subject, fromEmail);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            for (Map.Entry<String, String> entry : INLINE_IMAGES.entrySet()) {
                helper.addInline(entry.getKey(), new ClassPathResource(entry.getValue()));
            }
            mailSender.send(message);
            LOG.info("[email] sent to={} subject={} elapsed={}ms", to, subject, System.currentTimeMillis() - start);
        } catch (MessagingException e) {
            LOG.error("[email] failed to send to={} subject={} elapsed={}ms: {}",
                to, subject, System.currentTimeMillis() - start, e.getMessage(), e);
            throw new RuntimeException("Failed to send email to " + to, e);
        } catch (RuntimeException e) {
            LOG.error("[email] unexpected failure sending to={} subject={} elapsed={}ms: {}",
                to, subject, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    // ── Template builders ─────────────────────────────────────────────────────

    private String buildHtmlEmail(String content, String ctaUrl, String ctaLabel) {
        return getTemplate()
            .replace("{{CONTENT}}", content)
            .replace("{{CTA_URL}}", ctaUrl)
            .replace("{{CTA_LABEL}}", ctaLabel);
    }

    private String getTemplate() {
        if (template == null) {
            synchronized (this) {
                if (template == null) {
                    try (InputStream in = new ClassPathResource("templates/email-template.html")
                            .getInputStream()) {
                        template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        LOG.debug("[email] template loaded ({} bytes)", template.length());
                    } catch (IOException e) {
                        LOG.error("[email] failed to load email template: {}", e.getMessage(), e);
                        throw new RuntimeException("Failed to load email template", e);
                    }
                }
            }
        }
        return template;
    }

    private static String buildPasswordResetContent() {
        return """
                <h2 style="margin:0 0 16px;font-size:18px;font-weight:600;color:#08283B;">
                  Password Reset Request
                </h2>
                <p style="margin:0 0 8px;font-size:15px;line-height:1.6;color:#374151;">
                  We received a request to reset your password.
                  Click the button below to set a new password.
                </p>
                <p style="margin:0 0 24px;font-size:14px;line-height:1.6;color:#6B7280;">
                  This link expires in <strong>15 minutes</strong>.
                  If you did not request a password reset, you can safely ignore this email.
                </p>
                """;
    }
}
