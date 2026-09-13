package com.pixledgerapi.service;

import com.pixledgerapi.dto.AccountDTO;
import com.pixledgerapi.dto.AccountResponse;
import com.pixledgerapi.dto.EntryDTO;
import com.pixledgerapi.dto.EntryResponse;
import com.pixledgerapi.dto.LedgerEntryResponse;
import com.pixledgerapi.dto.TransferDTO;
import com.pixledgerapi.dto.TransferResponse;
import com.pixledgerapi.event.EntryCreatedEvent;
import com.pixledgerapi.model.Account;
import com.pixledgerapi.model.EntryType;
import com.pixledgerapi.model.LedgerEntry;
import com.pixledgerapi.repository.AccountRepository;
import com.pixledgerapi.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private static final int MAX_RETRIES = 3;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    // auto-referência com @Lazy: o retry precisa chamar o método transacional
    // através do proxy do Spring (self-invocation puro não passa pelo proxy)
    @Autowired
    @Lazy
    private AccountService self;

    public Account createAccount(AccountDTO dto) {
        Account account = new Account();
        account.setOwnerName(dto.ownerName());
        return accountRepository.save(account);
    }

    @Cacheable(value = "accounts", key = "#id")
    public AccountResponse getAccount(UUID id) {
        return AccountResponse.from(findAccountOrThrow(id));
    }

    @Cacheable(value = "ledgers", key = "#id.toString() + ':' + #pageable")
    public Page<LedgerEntryResponse> getLedger(UUID id, Pageable pageable) {
        findAccountOrThrow(id);
        return ledgerEntryRepository.findByAccountId(id, pageable).map(LedgerEntryResponse::from);
    }

    /**
     * Cria um lançamento com retry de optimistic lock.
     * Duas transações concorrentes lendo o mesmo saldo: a que commitar primeiro
     * atualiza o version; a outra recebe ObjectOptimisticLockingFailureException,
     * relê o saldo e tenta de novo (até MAX_RETRIES). Se ainda conflitar, 409.
     */
    public EntryResponse createEntry(UUID accountId, EntryDTO dto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            return createEntryWithRetry(accountId, dto, MAX_RETRIES);
        } catch (ResponseStatusException e) {
            result = entryResult(e.getStatusCode().value());
            throw e;
        } finally {
            sample.stop(entryTimer(result));
        }
    }

    private EntryResponse createEntryWithRetry(UUID accountId, EntryDTO dto, int attemptsLeft) {
        try {
            return self.createEntryTx(accountId, dto);
        } catch (ObjectOptimisticLockingFailureException e) {
            if (attemptsLeft <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Conflito de concorrência ao atualizar saldo, tente novamente");
            }
            return createEntryWithRetry(accountId, dto, attemptsLeft - 1);
        }
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "accounts", key = "#accountId"), // key inclui pageable, invalida todas as páginas da conta
            @CacheEvict(value = "ledgers", allEntries = true)
    })
    public EntryResponse createEntryTx(UUID accountId, EntryDTO dto) {
        Account account = findAccountOrThrow(accountId);

        if (account.getStatus() != Account.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conta não está ativa");
        }

        if (dto.entryType() == EntryType.DEBIT && account.getBalance().compareTo(dto.amount()) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Saldo insuficiente");
        }

        LedgerEntry entry = createLedgerEntry(account, dto.entryType(), dto.amount(), dto.description());

        if (dto.entryType() == EntryType.DEBIT) {
            account.setBalance(account.getBalance().subtract(dto.amount()));
        } else {
            account.setBalance(account.getBalance().add(dto.amount()));
        }
        accountRepository.save(account);

        publishEvent(entry);

        return new EntryResponse(entry, account.getBalance());
    }

    /**
     * Transferência PIX: débito na origem + crédito no destino no MESMO commit.
     * As contas são travadas com lock pessimista em ordem de UUID (determinística),
     * então transferências concorrentes se serializam sem deadlock nem perda de atualização.
     */
    @Transactional
    public TransferResponse transfer(TransferDTO dto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            return doTransfer(dto);
        } catch (ResponseStatusException e) {
            result = transferResult(e.getStatusCode().value());
            throw e;
        } finally {
            sample.stop(transferTimer(result));
        }
    }

    private TransferResponse doTransfer(TransferDTO dto) {
        if (dto.sourceAccountId().equals(dto.destinationAccountId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Conta de origem e destino devem ser diferentes");
        }

        List<UUID> ids = List.of(dto.sourceAccountId(), dto.destinationAccountId())
                .stream().sorted().toList();
        Map<UUID, Account> accounts = accountRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        Account source = accounts.get(dto.sourceAccountId());
        Account destination = accounts.get(dto.destinationAccountId());
        if (source == null || destination == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta não encontrada");
        }
        if (source.getStatus() != Account.Status.ACTIVE || destination.getStatus() != Account.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conta não está ativa");
        }
        if (source.getBalance().compareTo(dto.amount()) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Saldo insuficiente na conta de origem");
        }

        LedgerEntry debit = createLedgerEntry(source, EntryType.DEBIT, dto.amount(), dto.description());
        LedgerEntry credit = createLedgerEntry(destination, EntryType.CREDIT, dto.amount(), dto.description());

        source.setBalance(source.getBalance().subtract(dto.amount()));
        destination.setBalance(destination.getBalance().add(dto.amount()));
        accountRepository.saveAll(List.of(source, destination));

        publishEvent(debit);
        publishEvent(credit);

        // evict programático: as anotações @CacheEvict não suportam 2 chaves dinâmicas
        evictAccountCaches(dto.sourceAccountId(), dto.destinationAccountId());

        return new TransferResponse(debit.getId(), credit.getId(), source.getBalance(), destination.getBalance());
    }

    private LedgerEntry createLedgerEntry(Account account, EntryType entryType, BigDecimal amount, String description) {
        LedgerEntry entry = new LedgerEntry();
        entry.setAccount(account);
        entry.setEntryType(entryType);
        entry.setAmount(amount);
        entry.setDescription(description);
        return ledgerEntryRepository.save(entry);
    }

    private void publishEvent(LedgerEntry entry) {
        // evento de dominio publicado dentro da tx; o LedgerEventPublisher
        // (AFTER_COMMIT) so envia pro Kafka se o commit for confirmado
        applicationEventPublisher.publishEvent(new EntryCreatedEvent(
                entry.getId(), entry.getAccount().getId(),
                entry.getEntryType(), entry.getAmount(), entry.getDescription(), entry.getCreatedAt()));
    }

    private void evictAccountCaches(UUID... ids) {
        Cache accounts = cacheManager.getCache("accounts");
        if (accounts != null) {
            for (UUID id : ids) {
                accounts.evict(id);
            }
        }
        Cache ledgers = cacheManager.getCache("ledgers");
        if (ledgers != null) {
            ledgers.clear();
        }
    }

    private Timer transferTimer(String result) {
        return Timer.builder("ledger.transfer.duration")
                .tag("result", result)
                .register(meterRegistry);
    }

    private Timer entryTimer(String result) {
        return Timer.builder("ledger.entry.duration")
                .tag("result", result)
                .register(meterRegistry);
    }

    private String transferResult(int status) {
        return switch (status) {
            case 400 -> "same_account";
            case 404 -> "not_found";
            case 409 -> "inactive";
            case 422 -> "insufficient";
            default -> "http_" + status;
        };
    }

    private String entryResult(int status) {
        return switch (status) {
            case 404 -> "not_found";
            case 409 -> "conflict";
            case 422 -> "insufficient";
            default -> "http_" + status;
        };
    }

    private Account findAccountOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta não encontrada"));
    }
}