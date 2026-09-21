package com.example.profileservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Development implementation that logs confirmation URLs instead of sending emails.
 * Replace with actual email implementation for production.
 * <p>
 * Restricted to {@code !prod} so a production deployment can never silently log a real user's
 * confirmation token instead of emailing it: with no other {@link EmailService} bean, the
 * {@code prod} profile fails to start instead (see docs/PRODUCTION_CHECKLIST.md).
 */
@Service
@Profile("!prod")
@Slf4j
public class LoggingEmailService implements EmailService {

    @Override
    public void sendConfirmationEmail(String to, String confirmationUrl) {
        log.info("========================================");
        log.info("CONFIRMATION EMAIL");
        log.info("To: {}", to);
        log.info("Click to confirm: {}", confirmationUrl);
        log.info("========================================");
    }
}
