package com.pixledgerapi.dto;

public record LoginResponse(
        String accessToken,
        long expiresIn
) {
}