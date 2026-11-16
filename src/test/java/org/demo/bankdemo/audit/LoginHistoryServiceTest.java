package org.demo.bankdemo.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginHistoryServiceTest {

    @Test
    void createsSecurityAlertWhenSuccessfulLoginUsesNewDevice() {
        var repository = Mockito.mock(LoginHistoryRepository.class);
        var userRepository = Mockito.mock(UserRepository.class);
        var notificationService = Mockito.mock(NotificationService.class);
        var user = new User("anna@example.pl", "hash", "Anna", "Nowak", null);
        var previous = new LoginHistory(null, "anna@example.pl", "10.0.0.1", "Old browser", true, null);
        var request = Mockito.mock(HttpServletRequest.class);
        var session = Mockito.mock(HttpSession.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(request.getHeader("User-Agent")).thenReturn("New browser");
        when(request.getSession(true)).thenReturn(session);
        when(repository.findTop50ByEmailAndSuccessfulTrueOrderByCreatedAtDesc("anna@example.pl"))
                .thenReturn(List.of(previous));
        when(userRepository.findByEmail("anna@example.pl")).thenReturn(Optional.of(user));

        var service = new LoginHistoryService(repository, userRepository, notificationService);

        service.record("ANNA@example.pl", request, true, null);

        verify(notificationService).securityEvent(eq("anna@example.pl"), contains("nowego urzadzenia"));
        verify(repository).save(Mockito.any(LoginHistory.class));
    }
}
