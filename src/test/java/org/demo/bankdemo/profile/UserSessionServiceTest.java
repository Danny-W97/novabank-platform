package org.demo.bankdemo.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSessionServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void invalidatesEverySessionExceptTheCurrentOne() {
        var provider = mock(ObjectProvider.class);
        var repository = mock(RedisIndexedSessionRepository.class);
        Map<String, RedisIndexedSessionRepository.RedisSession> sessions = mock(Map.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        when(repository.findByPrincipalName("anna.nowak@example.pl"))
                .thenReturn(sessions);
        when(sessions.keySet()).thenReturn(Set.of("current-session", "other-1", "other-2"));
        var service = new UserSessionService(provider);

        var removed = service.invalidateOtherSessions(
                "anna.nowak@example.pl",
                "current-session"
        );

        assertThat(removed).isEqualTo(2);
        verify(repository).deleteById("other-1");
        verify(repository).deleteById("other-2");
        verify(repository, never()).deleteById("current-session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsExplicitlyWhenIndexedRedisRepositoryIsUnavailable() {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var service = new UserSessionService(provider);

        assertThatThrownBy(() -> service.invalidateOtherSessions("user@example.pl", "current"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis");
    }
}
