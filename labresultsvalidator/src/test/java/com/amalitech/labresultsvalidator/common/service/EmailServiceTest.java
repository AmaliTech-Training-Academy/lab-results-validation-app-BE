package com.amalitech.labresultsvalidator.common.service;

import com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;

    @InjectMocks
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "noreply@labgate.com");
        ReflectionTestUtils.setField(emailService, "frontendUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(emailService, "baseUrl", "http://localhost:8080");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
    }

    @Test
    void onInstructorProvisioned_sendsToCorrectRecipient() throws Exception {
        emailService.onInstructorProvisioned(
            new InstructorProvisionedEvent("instructor@test.com", "TempPass1!"));

        MimeMessage sent = captureMessage();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("instructor@test.com");
        assertThat(sent.getFrom()[0].toString()).isEqualTo("noreply@labgate.com");
    }

    @Test
    void onInstructorProvisioned_subjectContainsAppName() throws Exception {
        emailService.onInstructorProvisioned(
            new InstructorProvisionedEvent("user@test.com", "MySecret99"));

        assertThat(captureMessage().getSubject()).containsIgnoringCase("Amalitech Training Validata");
    }

    @Test
    void onInstructorProvisioned_bodyContainsCredentials() throws Exception {
        emailService.onInstructorProvisioned(
            new InstructorProvisionedEvent("user@test.com", "MySecret99"));

        String body = (String) captureMessage().getContent();
        assertThat(body).contains("user@test.com");
        assertThat(body).contains("MySecret99");
    }

    @Test
    void onInstructorProvisioned_bodyIsHtmlWithBranding() throws Exception {
        emailService.onInstructorProvisioned(
            new InstructorProvisionedEvent("user@test.com", "MySecret99"));

        String body = (String) captureMessage().getContent();
        assertThat(body).contains("#08283B");
        assertThat(body).contains("Amalitech Training Validata");
    }

    @Test
    void sendPasswordResetEmail_sendsToCorrectRecipient() throws Exception {
        emailService.sendPasswordResetEmail("reset@test.com", "https://example.com/reset?token=abc");

        MimeMessage sent = captureMessage();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("reset@test.com");
    }

    @Test
    void sendPasswordResetEmail_bodyContainsResetLink() throws Exception {
        String link = "https://example.com/reset?token=abc";
        emailService.sendPasswordResetEmail("reset@test.com", link);

        String body = (String) captureMessage().getContent();
        assertThat(body).contains(link);
    }

    @Test
    void sendPasswordResetEmail_bodyContainsExpiryWarning() throws Exception {
        emailService.sendPasswordResetEmail("reset@test.com", "https://example.com/reset");

        String body = (String) captureMessage().getContent();
        assertThat(body).contains("15 minutes");
    }

    private MimeMessage captureMessage() {
        org.mockito.ArgumentCaptor<MimeMessage> captor =
            org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
