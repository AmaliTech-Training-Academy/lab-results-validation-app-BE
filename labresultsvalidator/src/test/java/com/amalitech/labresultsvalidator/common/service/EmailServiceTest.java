package com.amalitech.labresultsvalidator.common.service;

import com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;

    @InjectMocks
    private EmailService emailService;

    @Test
    void onInstructorProvisioned_sendsEmailWithCorrectRecipientAndSubject() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "noreply@labgate.com");

        emailService.onInstructorProvisioned(new InstructorProvisionedEvent("instructor@test.com", "TempPass1!"));

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage sent = messageCaptor.getValue();
        assertThat(sent.getTo()).containsExactly("instructor@test.com");
        assertThat(sent.getFrom()).isEqualTo("noreply@labgate.com");
        assertThat(sent.getSubject()).containsIgnoringCase("LabGate");
    }

    @Test
    void onInstructorProvisioned_includesCredentialsInBody() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "noreply@labgate.com");

        emailService.onInstructorProvisioned(new InstructorProvisionedEvent("user@test.com", "MySecret99"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        String body = captor.getValue().getText();
        assertThat(body).contains("user@test.com");
        assertThat(body).contains("MySecret99");
    }
}
