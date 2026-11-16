package org.demo.bankdemo.web;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduledTransferControllerTest {

    @Test
    void convertsUnambiguousPolishLocalTime() {
        assertThat(ScheduledTransferController.firstExecutionInstant(
                LocalDateTime.of(2026, 3, 29, 9, 0)))
                .isEqualTo("2026-03-29T07:00:00Z");
    }

    @Test
    void rejectsMissingAndRepeatedHourOnClockChange() {
        assertThatThrownBy(() -> ScheduledTransferController.firstExecutionInstant(
                LocalDateTime.of(2026, 3, 29, 2, 30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduledTransferController.firstExecutionInstant(
                LocalDateTime.of(2026, 10, 25, 2, 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
