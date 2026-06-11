package com.amalitech.labresultsvalidator.common.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public void sendInstructorWelcome(String toEmail, String temporaryPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("Welcome to LabGate — Your Account Details");
        message.setText(buildWelcomeBody(toEmail, temporaryPassword));
        mailSender.send(message);
    }

    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("LabGate — Password Reset Request");
        message.setText(buildPasswordResetBody(resetLink));
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

    private String buildPasswordResetBody(String resetLink) {
        return String.format(
            "Hello,%n%n"
            + "We received a request to reset your LabGate password.%n%n"
            + "Click the link below to set a new password:%n"
            + "%s%n%n"
            + "This link expires in 15 minutes. If you did not request a password reset, "
            + "you can safely ignore this email.%n%n"
            + "Best regards,%n"
            + "LabGate Admin Team",
            resetLink
        );
    }
}
