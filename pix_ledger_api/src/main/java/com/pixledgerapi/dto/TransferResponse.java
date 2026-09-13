package com.pixledgerapi.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResponse(
        UUID sourceEntryId,
        UUID destinationEntryId,
        BigDecimal sourceBalance,
        BigDecimal destinationBalance
) {
}