package com.amalitech.labresultsvalidator.common.service;

import com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private volatile String template;

    // ── Transactional event handlers ──────────────────────────────────────────

    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInstructorProvisioned(InstructorProvisionedEvent event) {
        dispatch(
            event.email(),
            "Welcome to Amalitech Training Validata — Your Account Details",
            buildHtmlEmail(
                buildWelcomeContent(event.email(), event.temporaryPassword()),
                frontendUrl,
                "Sign In to Validata"
            )
        );
    }

    @Async("emailTaskExecutor")
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
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

    // ── Shared internal dispatcher ────────────────────────────────────────────

    private static final Map<String, String> INLINE_IMAGES = new LinkedHashMap<>();

    static {
        INLINE_IMAGES.put("logo-header", "static/images/email/logo-header.png");
        INLINE_IMAGES.put("header-art",  "static/images/email/header-art.png");
        INLINE_IMAGES.put("logo-dark",   "static/images/email/logo-dark.png");
    }

    private void dispatch(String to, String subject, String htmlBody) {
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
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email to " + to, e);
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
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load email template", e);
                    }
                }
            }
        }
        return template;
    }

    private static String buildWelcomeContent(String email, String temporaryPassword) {
        return """
                <p style="margin:0 0 4px;font-size:22px;font-weight:700;color:#08283B;">
                  Welcome aboard!
                </p>
                <p style="margin:0 0 20px;font-size:15px;line-height:1.6;color:#374151;">
                  Your instructor account on Amalitech Training Validata is ready.
                  Use the credentials below to sign in — you will be prompted to set a new password on your first login.
                </p>
                <table cellpadding="0" cellspacing="0" width="100%%"
                       style="margin:0 0 24px;background-color:#F0F4F8;border-radius:6px;">
                  <tr>
                    <td style="padding:20px 24px;border-left:4px solid #08283B;border-radius:6px;">
                      <p style="margin:0 0 14px;font-size:11px;font-weight:700;color:#6B7280;\
                      text-transform:uppercase;letter-spacing:1px;">Your Login Credentials</p>
                      <p style="margin:0 0 2px;font-size:12px;font-weight:600;color:#6B7280;\
                      text-transform:uppercase;letter-spacing:0.5px;">Email</p>
                      <p style="margin:0 0 14px;font-size:15px;font-weight:600;color:#08283B;">%s</p>
                      <p style="margin:0 0 2px;font-size:12px;font-weight:600;color:#6B7280;\
                      text-transform:uppercase;letter-spacing:0.5px;">Temporary Password</p>
                      <p style="margin:0;font-size:16px;font-weight:700;color:#08283B;\
                      font-family:'Courier New',Courier,monospace;letter-spacing:2px;">%s</p>
                    </td>
                  </tr>
                </table>
                <p style="margin:0 0 8px;font-size:14px;line-height:1.6;color:#374151;">
                  <strong>Important:</strong> This is a temporary password. You will be \
                  required to change it upon your first login.
                </p>
                <p style="margin:0;font-size:13px;line-height:1.6;color:#6B7280;">
                  If you were not expecting this email, please disregard it or contact your administrator.
                </p>
                """.formatted(email, temporaryPassword);
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
