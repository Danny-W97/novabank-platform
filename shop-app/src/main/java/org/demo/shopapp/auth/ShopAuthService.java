package org.demo.shopapp.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopAuthService {

    private final ShopUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ShopAuthService(ShopUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void register(RegisterRequest request) {
        if (!request.passwordsMatch()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match.");
        }
        var email = request.email().strip().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered.");
        }
        userRepository.create(
                email,
                passwordEncoder.encode(request.password()),
                email
        );
    }
}
