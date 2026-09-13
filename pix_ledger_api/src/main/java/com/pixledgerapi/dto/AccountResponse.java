package com.pixledgerapi.dto;

import com.pixledgerapi.model.Account;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Modelo de leitura da conta: é o que vai para o cache Redis e para o JSON da API.
 * Record (Serializable) em vez da entidade JPA — o JdkSerialization do cache
 * default do Boot não serializa entidade (proxy LAZY, @Version, coleções).
 */
public record AccountResponse(
        UUID id,
        String ownerName,
        Account.Status status,
        BigDecimal balance,
        Instant createdAt,
        Instant updatedAt,
        Long version
) implements Serializable {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(), account.getOwnerName(), account.getStatus(),
                account.getBalance(), account.getCreatedAt(), account.getUpdatedAt(),
                account.getVersion());
    }
}