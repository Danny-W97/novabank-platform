package org.demo.bankdemo.auth;

import org.demo.bankdemo.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class LoginService {

    private final UserRepository userRepository;

    public LoginService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void recordSuccessfulLogin(String email) {
        userRepository.findByEmail(RegistrationService.normalizeEmail(email))
                .ifPresent(user -> user.recordSuccessfulLogin(Instant.now()));
    }
}
