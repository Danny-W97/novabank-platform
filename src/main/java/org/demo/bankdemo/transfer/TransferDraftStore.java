package org.demo.bankdemo.transfer;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class TransferDraftStore {

    private static final String ATTRIBUTE_NAME = TransferDraftStore.class.getName() + ".drafts";
    private static final int MAX_DRAFTS_PER_SESSION = 10;

    public void save(HttpSession session, TransferCommand command) {
        synchronized (session) {
            var drafts = new LinkedHashMap<>(drafts(session));
            drafts.put(command.idempotencyKey(), command);
            while (drafts.size() > MAX_DRAFTS_PER_SESSION) {
                drafts.remove(drafts.keySet().iterator().next());
            }
            session.setAttribute(ATTRIBUTE_NAME, drafts);
        }
    }

    public Optional<TransferCommand> find(HttpSession session, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        synchronized (session) {
            return Optional.ofNullable(drafts(session).get(idempotencyKey));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TransferCommand> drafts(HttpSession session) {
        var value = session.getAttribute(ATTRIBUTE_NAME);
        if (value instanceof Map<?, ?>) {
            return (Map<String, TransferCommand>) value;
        }
        return Map.of();
    }
}
