package org.demo.bankdemo.transfer;

import java.time.Instant;

/** Immutable scheduler cursor, without loading users or mutable managed schedules. */
public record DueOccurrence(Long id, Instant dueAt) { }
