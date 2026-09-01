package com.amalitech.labresultsvalidator.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.net.ServerSocket;
import java.io.UncheckedIOException;

/**
 * A real SMTP server for the notification tests, started once for the whole suite.
 *
 * <p>Epic C is entirely about <em>what gets emailed and when</em>. A mocked {@code JavaMailSender}
 * would only prove the code called a method; it would not catch a notification addressed to the
 * wrong person, an empty body, or a send that silently never happened — and "no notification is
 * ever addressed to a learner" (C10 AC1) is a claim about a delivered message, not about a call.
 *
 * <p>Started on a free port chosen at class-init, because the Spring context binds
 * {@code spring.mail.port} once and every test class shares it.
 */
public final class TestMailServer {

    private static final int PORT = freePort();
    private static final GreenMail SERVER = start();

    private TestMailServer() {
    }

    public static int port() {
        return PORT;
    }

    public static String host() {
        return "127.0.0.1";
    }

    /** Every message the application has sent since the last {@link #reset()}. */
    public static MimeMessage[] received() {
        return SERVER.getReceivedMessages();
    }

    /**
     * Blocks until at least {@code count} messages have arrived, or the timeout expires.
     * Dispatch is synchronous inside {@code sendNow}, but delivery to the SMTP server is not
     * instantaneous — asserting without waiting is the classic source of a flaky mail test.
     *
     * @return true if the messages arrived in time
     */
    public static boolean awaitMessages(int count, long timeoutMillis) {
        return SERVER.waitForIncomingEmail(timeoutMillis, count);
    }

    /**
     * Messages delivered to one address.
     *
     * <p>Prefer this over counting {@link #received()} wholesale. The mailbox is shared and some
     * dispatch is asynchronous — an admin run digest is AUTO and goes out on an AFTER_COMMIT
     * listener, so a message caused by one test can land while a later one is asserting. Scoping by
     * recipient is what makes a mail assertion stable in a full-suite run; counting everything
     * passes alone and fails in the suite.
     */
    public static java.util.List<MimeMessage> messagesTo(String address) {
        java.util.List<MimeMessage> matches = new java.util.ArrayList<>();
        for (MimeMessage message : received()) {
            try {
                for (jakarta.mail.Address recipient : message.getAllRecipients()) {
                    if (recipient.toString().equalsIgnoreCase(address)) {
                        matches.add(message);
                        break;
                    }
                }
            } catch (Exception ignored) {
                // A message we cannot read the recipients of is not a message addressed to us.
            }
        }
        return matches;
    }

    /** Blocks until at least {@code count} messages have arrived for one address. */
    public static boolean awaitMessagesTo(String address, int count, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (messagesTo(address).size() >= count) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return messagesTo(address).size() >= count;
    }

    /** Clears the mailbox so one test cannot see another's messages. */
    public static void reset() {
        SERVER.reset();
    }

    private static GreenMail start() {
        // The user must match spring.mail.username / spring.mail.password exactly. JavaMailSenderImpl
        // hands a non-null username to Transport.connect(), which negotiates AUTH regardless of
        // mail.smtp.auth — and the username cannot simply be dropped, because EmailService uses it
        // as the From address. A mismatch here surfaces as "Authentication failed" on every send,
        // which reads like a defect in the dispatch code and is not one.
        // Authentication disabled so AUTH is never negotiated. The username cannot simply be
        // dropped instead — EmailService uses spring.mail.username as the From address — and with
        // a username set, JavaMailSenderImpl hands it to Transport.connect() and authenticates
        // whatever mail.smtp.auth says. Every other combination fails every send with a misleading
        // "Authentication failed" that looks like a defect in the dispatch code.
        GreenMail server = new GreenMail(new ServerSetup(PORT, host(), ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not reserve a port for the test SMTP server", ex);
        }
    }
}
