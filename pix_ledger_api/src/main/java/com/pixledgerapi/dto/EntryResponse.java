package com.pixledgerapi.dto;

import com.pixledgerapi.model.LedgerEntry;

import java.math.BigDecimal;

public record EntryResponse(
        LedgerEntry entry,
        BigDecimal balance
) {
}