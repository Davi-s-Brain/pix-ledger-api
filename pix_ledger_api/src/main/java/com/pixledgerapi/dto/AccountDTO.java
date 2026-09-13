package com.pixledgerapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountDTO(
        @NotBlank(message = "ownerName é obrigatório")
        @Size(max = 255)
        String ownerName
) {
}