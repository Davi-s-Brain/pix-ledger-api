package com.pixledgerapi.repository;

import com.pixledgerapi.model.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    // @EntityGraph busca account junto -> 1 query (fix N+1)
    @EntityGraph(attributePaths = "account")
    Page<LedgerEntry> findByAccountId(UUID accountId, Pageable pageable);
}