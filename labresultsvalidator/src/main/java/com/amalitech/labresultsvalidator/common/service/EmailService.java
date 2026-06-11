package com.amalitech.labresultsvalidator.common.service;

import com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    // ── Transactional event handlers ──────────────────────────────────────────

    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInstructorProvisioned(InstructorProvisionedEvent event) {
        dispatch(
            event.email(),
            "Welcome to LabGate — Your Account Details",
            buildInstructorWelcomeBody(event.email(), event.temporaryPassword())
        );
    }

    // ── Shared internal dispatcher ────────────────────────────────────────────

    private void dispatch(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    // ── Message body builders ─────────────────────────────────────────────────

    private String buildInstructorWelcomeBody(String email, String temporaryPassword) {
        return String.format(
            "Hello,%n%n"
            + "Your instructor account has been created for LabGate.%n%n"
            + "Email: %s%n"
            + "Temporary Password: %s%n%n"
            + "You will be required to change this password upon first login.%n%n"
            + "Best regards,%n"
            + "LabGate Admin Team",
            email, temporaryPassword
        );
    }
}
