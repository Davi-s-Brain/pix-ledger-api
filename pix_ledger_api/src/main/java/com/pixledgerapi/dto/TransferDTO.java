package com.pixledgerapi.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferDTO(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotNull @DecimalMin(value = "0.01", message = "amount deve ser maior que zero") BigDecimal amount,
        @Size(max = 255) String description
) {
}