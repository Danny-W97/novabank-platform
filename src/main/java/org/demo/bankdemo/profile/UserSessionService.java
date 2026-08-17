package org.demo.bankdemo.profile;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.stereotype.Service;

@Service
public class UserSessionService {

    private final ObjectProvider<RedisIndexedSessionRepository> repositoryProvider;

    public UserSessionService(
            ObjectProvider<RedisIndexedSessionRepository> repositoryProvider
    ) {
        this.repositoryProvider = repositoryProvider;
    }

    public int invalidateOtherSessions(String principalName, String currentSessionId) {
        var removed = 0;
        var repository = repository();
        for (var sessionId : repository.findByPrincipalName(principalName).keySet()) {
            if (!sessionId.equals(currentSessionId)) {
                repository.deleteById(sessionId);
                removed++;
            }
        }
        return removed;
    }

    public int invalidateAllSessions(String principalName) {
        var repository = repository();
        var removed = 0;
        for (var sessionId : repository.findByPrincipalName(principalName).keySet()) {
            repository.deleteById(sessionId);
            removed++;
        }
        return removed;
    }

    public int activeSessionCount(String principalName) {
        try {
            var repository = repositoryProvider.getIfAvailable();
            if (repository == null) {
                return 0;
            }
            return repository.findByPrincipalName(principalName).size();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private RedisIndexedSessionRepository repository() {
        var repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("Indeksowany magazyn sesji Redis nie jest dostępny.");
        }
        return repository;
    }
}
