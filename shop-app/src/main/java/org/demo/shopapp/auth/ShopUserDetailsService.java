package org.demo.shopapp.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ShopUserDetailsService implements UserDetailsService {

    private final ShopUserRepository userRepository;

    public ShopUserDetailsService(ShopUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var email = username == null ? "" : username.strip().toLowerCase();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid shop credentials."));
    }
}
