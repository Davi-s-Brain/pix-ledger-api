package com.pixledgerapi.controller;

import com.pixledgerapi.dto.TransferDTO;
import com.pixledgerapi.dto.TransferResponse;
import com.pixledgerapi.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    @Autowired
    private AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse createTransfer(@Valid @RequestBody TransferDTO dto) {
        return accountService.transfer(dto);
    }
}