package org.demo.bankdemo.admin;

import org.demo.bankdemo.user.UserRepository;
import org.demo.bankdemo.user.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
public class AdminRoleInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final String adminEmail;

    public AdminRoleInitializer(
            UserRepository userRepository,
            @Value("${app.admin.email:}") String adminEmail
    ) {
        this.userRepository = userRepository;
        this.adminEmail = adminEmail;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            return;
        }
        userRepository.findByEmail(adminEmail.strip().toLowerCase(Locale.ROOT))
                .ifPresent(user -> user.changeRole(UserRole.ADMIN));
    }
}
