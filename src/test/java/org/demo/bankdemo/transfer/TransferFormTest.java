package org.demo.bankdemo.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferFormTest {

    @Test
    void normalizesPolishAccountNumberAmountAndText() {
        var form = new TransferForm();
        form.setIdempotencyKey("550e8400-e29b-41d4-a716-446655440000");
        form.setRecipientName("  Anna   Nowak ");
        form.setDestinationAccountNumber("PL 12 1279 4313 8124 6631 4704 2900");
        form.setAmount("125,5");
        form.setTitle("  Zwrot   za obiad ");

        var command = form.toCommand();

        assertThat(command.recipientName()).isEqualTo("Anna Nowak");
        assertThat(command.destinationAccountNumber()).isEqualTo("12127943138124663147042900");
        assertThat(command.amount()).isEqualByComparingTo("125.50");
        assertThat(command.title()).isEqualTo("Zwrot za obiad");
    }
}
