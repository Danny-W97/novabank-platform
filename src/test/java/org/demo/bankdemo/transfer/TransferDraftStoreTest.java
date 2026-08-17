package org.demo.bankdemo.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransferDraftStoreTest {

    @Test
    void storesConfirmedCommandInHttpSession() {
        var store = new TransferDraftStore();
        var session = new MockHttpSession();
        var command = new TransferCommand(
                "550e8400-e29b-41d4-a716-446655440000",
                "Anna Nowak",
                "12127943138124663147042900",
                new BigDecimal("25.00"),
                "Test"
        );

        store.save(session, command);

        assertThat(store.find(session, command.idempotencyKey()))
                .contains(command);
    }
}
