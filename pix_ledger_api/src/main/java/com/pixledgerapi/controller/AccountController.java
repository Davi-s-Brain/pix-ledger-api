package com.pixledgerapi.controller;

import com.pixledgerapi.dto.AccountDTO;
import com.pixledgerapi.dto.AccountResponse;
import com.pixledgerapi.dto.EntryDTO;
import com.pixledgerapi.dto.EntryResponse;
import com.pixledgerapi.dto.LedgerEntryResponse;
import com.pixledgerapi.model.Account;
import com.pixledgerapi.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    @Autowired
    private AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account createAccount(@Valid @RequestBody AccountDTO dto) {
        return accountService.createAccount(dto);
    }

    @PostMapping("/{id}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse createEntry(@PathVariable UUID id, @Valid @RequestBody EntryDTO dto) {
        return accountService.createEntry(id, dto);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return accountService.getAccount(id);
    }

    @GetMapping("/{id}/ledger")
    public Page<LedgerEntryResponse> getLedger(@PathVariable UUID id, Pageable pageable) {
        return accountService.getLedger(id, pageable);
    }
}