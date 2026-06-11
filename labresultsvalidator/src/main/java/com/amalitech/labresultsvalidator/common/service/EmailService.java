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

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInstructorProvisioned(InstructorProvisionedEvent event) {
        sendInstructorWelcome(event.email(), event.temporaryPassword());
    }

    private void sendInstructorWelcome(String toEmail, String temporaryPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("Welcome to LabGate — Your Account Details");
        message.setText(buildWelcomeBody(toEmail, temporaryPassword));
        mailSender.send(message);
    }

    private String buildWelcomeBody(String email, String temporaryPassword) {
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
