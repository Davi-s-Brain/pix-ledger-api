package com.pixledgerapi.dto;

import com.pixledgerapi.model.EntryType;
import com.pixledgerapi.model.LedgerEntry;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Modelo de leitura de um lançamento do extrato (ledger) — cacheável.
 * Espelha o JSON da entidade LedgerEntry sem o proxy LAZY de conta.
 */
public record LedgerEntryResponse(
        UUID id,
        EntryType entryType,
        BigDecimal amount,
        String description,
        Instant createdAt
) implements Serializable {

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(), entry.getEntryType(), entry.getAmount(),
                entry.getDescription(), entry.getCreatedAt());
    }
}