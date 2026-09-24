package com.aicompany.backend.user.service;

import com.aicompany.backend.user.model.AppUser;
import com.aicompany.backend.user.model.Role;
import com.aicompany.backend.user.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * The first admin (ADR-024 §4). With no admin in the database, one is created
 * at startup:
 *
 * <ul>
 *   <li>with {@code aicos.security.admin.password} (environment
 *       {@code AICOS_ADMIN_PASSWORD}, or the local secrets file) when it is set;</li>
 *   <li>otherwise with a random password, written -- and only written -- to
 *       {@code <aicos.home>/admin-initial-password.txt}, outside the repository,
 *       and marked "must change". The log names the file, never the password.</li>
 * </ul>
 *
 * <p>Recovery: {@code aicos.security.admin.reset=true} with a password set
 * resets that admin's password at startup and ends their sessions.
 */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityLog log;
    private final TransactionTemplate transactions;
    private final String username;
    private final String password;
    private final boolean reset;
    private final Path home;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, SecurityLog log,
                          TransactionTemplate transactions,
                          @Value("${aicos.security.admin.username:admin}") String username,
                          @Value("${aicos.security.admin.password:}") String password,
                          @Value("${aicos.security.admin.reset:false}") boolean reset,
                          @Value("${aicos.home:${user.home}/.aicos}") String home) {
        this.users = users;
        this.encoder = encoder;
        this.log = log;
        this.transactions = transactions;
        this.username = AuthService.normalize(username);
        this.password = password == null ? "" : password;
        this.reset = reset;
        this.home = Path.of(home);
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            if (!users.existsByRole(Role.ADMIN)) {
                create();
            } else if (reset && !password.isBlank()) {
                resetPassword();
            }
        });
    }

    private void create() {
        boolean generated = password.isBlank();
        String secret = generated ? generate() : password;
        if (!generated) {
            PasswordPolicy.check(secret, username);
        }
        users.save(new AppUser(username, "Administrator", encoder.encode(secret), Role.ADMIN, generated));
        if (generated) {
            Path file = home.resolve("admin-initial-password.txt");
            write(file, "username=" + username + System.lineSeparator() + "password=" + secret + System.lineSeparator());
            LOG.warn("Created the admin '{}' with a generated password, written to {} -- sign in and change it",
                    username, file);
            log.record(SecurityLog.ADMIN_BOOTSTRAPPED, username, "startup", "generated password written to " + file);
        } else {
            LOG.info("Created the admin '{}' with the configured password", username);
            log.record(SecurityLog.ADMIN_BOOTSTRAPPED, username, "startup", "configured password");
        }
    }

    private void resetPassword() {
        PasswordPolicy.check(password, username);
        AppUser admin = users.findByUsernameForUpdate(username).orElse(null);
        if (admin == null || admin.getRole() != Role.ADMIN) {
            LOG.warn("aicos.security.admin.reset is set, but '{}' is not an admin: nothing reset", username);
            return;
        }
        admin.changePassword(encoder.encode(password), false);
        LOG.warn("The password of the admin '{}' was reset from the configuration", username);
        log.record(SecurityLog.PASSWORD_RESET, username, "startup", "reset from configuration");
    }

    private static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 5 == 0) {
                value.append('-');
            }
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        } catch (IOException e) {
            throw new IllegalStateException("The generated admin password could not be written to " + file, e);
        }
    }
}
