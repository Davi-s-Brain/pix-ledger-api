package com.pixledgerapi.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "name é obrigatório")
        String name,
        @NotBlank(message = "password é obrigatório")
        String password
) {
}