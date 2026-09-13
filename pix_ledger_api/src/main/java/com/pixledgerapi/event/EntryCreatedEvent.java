package com.pixledgerapi.event;

import com.pixledgerapi.model.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de domínio publicado quando um lançamento é confirmado.
 * Usado tanto como evento de aplicação (AFTER_COMMIT) quanto como payload do Kafka.
 */
public record EntryCreatedEvent(
        UUID entryId,
        UUID accountId,
        EntryType entryType,
        BigDecimal amount,
        String description,
        Instant createdAt
) {
}