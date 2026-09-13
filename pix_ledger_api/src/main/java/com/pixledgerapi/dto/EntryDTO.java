package com.pixledgerapi.dto;

import com.pixledgerapi.model.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record EntryDTO(
        @NotNull(message = "entryType é obrigatório")
        EntryType entryType,
        @NotNull(message = "amount é obrigatório")
        @DecimalMin(value = "0.01", message = "amount deve ser maior que zero")
        BigDecimal amount,
        @Size(max = 255, message = "description deve ter no máximo 255 caracteres")
        String description
) {
}