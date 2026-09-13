package com.pixledgerapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixledgerapi.model.Account;
import com.pixledgerapi.model.EntryType;
import com.pixledgerapi.model.LedgerEntry;
import com.pixledgerapi.repository.AccountRepository;
import com.pixledgerapi.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String bearerToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }

    private Account newAccount(String owner, Account.Status status) {
        Account account = new Account();
        account.setOwnerName(owner);
        account.setStatus(status);
        return accountRepository.save(account);
    }

    private Account fundAccount(Account account, String amount, String token) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryType\":\"CREDIT\",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private String transferBody(UUID source, UUID destination, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
                + "\",\"amount\":" + amount + "}";
    }

    @BeforeEach
    void cleanDb() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void transfer_movesMoneyFromSourceToDestination() throws Exception {
        String token = bearerToken();
        Account source = fundAccount(newAccount("origem", Account.Status.ACTIVE), "100.00", token);
        Account destination = newAccount("destino", Account.Status.ACTIVE);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getId(), destination.getId(), "60.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceBalance").value(40.00))
                .andExpect(jsonPath("$.destinationBalance").value(60.00));

        Account sourceAfter = accountRepository.findById(source.getId()).orElseThrow();
        Account destinationAfter = accountRepository.findById(destination.getId()).orElseThrow();
        assertThat(sourceAfter.getBalance()).isEqualByComparingTo("40.00");
        assertThat(destinationAfter.getBalance()).isEqualByComparingTo("60.00");

        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        // crédito de fundo (1) + débito origem + crédito destino (2)
        assertThat(entries).hasSize(3);
        assertThat(entries).anyMatch(e -> e.getEntryType() == EntryType.DEBIT
                && e.getAccount().getId().equals(source.getId())
                && e.getAmount().compareTo(new BigDecimal("60.00")) == 0);
        assertThat(entries).anyMatch(e -> e.getEntryType() == EntryType.CREDIT
                && e.getAccount().getId().equals(destination.getId()));
    }

    @Test
    void transfer_insufficientFunds_isAtomic() throws Exception {
        String token = bearerToken();
        Account source = fundAccount(newAccount("origem", Account.Status.ACTIVE), "50.00", token);
        Account destination = newAccount("destino", Account.Status.ACTIVE);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getId(), destination.getId(), "100.00")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance()).isEqualByComparingTo("50.00");
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalance()).isEqualByComparingTo("0");
        // só o crédito de fundo permanece; a transferência falhou sem gravar NADA (atomicidade)
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAccount().getId()).isEqualTo(source.getId());
    }

    @Test
    void transfer_sourceInactive_returns409() throws Exception {
        String token = bearerToken();
        Account source = newAccount("origem", Account.Status.BLOCKED);
        Account destination = newAccount("destino", Account.Status.ACTIVE);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getId(), destination.getId(), "10.00")))
                .andExpect(status().isConflict());

        assertThat(ledgerEntryRepository.findAll()).isEmpty();
    }

    @Test
    void transfer_destinationInactive_returns409() throws Exception {
        String token = bearerToken();
        Account source = fundAccount(newAccount("origem", Account.Status.ACTIVE), "100.00", token);
        Account destination = newAccount("destino", Account.Status.CLOSED);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getId(), destination.getId(), "10.00")))
                .andExpect(status().isConflict());

        // só o crédito de fundo permanece; a transferência bloqueada não gravou nada
        assertThat(ledgerEntryRepository.findAll()).hasSize(1);
    }

    @Test
    void transfer_unknownAccount_returns404() throws Exception {
        String token = bearerToken();
        Account source = fundAccount(newAccount("origem", Account.Status.ACTIVE), "100.00", token);
        UUID ghost = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getId(), ghost, "10.00")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ghost, source.getId(), "10.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void transfer_sameAccount_returns400() throws Exception {
        String token = bearerToken();
        Account source = fundAccount(newAccount("origem", Account.Status.ACTIVE), "100.00", token);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(source.getId(), source.getId(), "10.00")))
                .andExpect(status().isBadRequest());
    }

    /**
     * Concorrência: 2 transferências de 80 saindo de uma conta com 100.
     * Com o lock pessimista elas se serializam — uma commita, a outra vê saldo 20 e leva 422.
     * Nada de saldo negativo nem duplo débito: origem termina com 20 e só 1 transferência vingou.
     */
    @Test
    void transfer_concurrent_serializesWithoutLostUpdate() throws Exception {
        String token = bearerToken();
        Account source = fundAccount(newAccount("origem", Account.Status.ACTIVE), "100.00", token);
        Account destinationA = newAccount("destinoA", Account.Status.ACTIVE);
        Account destinationB = newAccount("destinoB", Account.Status.ACTIVE);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        for (Account destination : List.of(destinationA, destinationB)) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    int status = mockMvc.perform(post("/api/v1/transfers")
                                    .header("Authorization", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(transferBody(source.getId(), destination.getId(), "80.00")))
                            .andReturn().getResponse().getStatus();
                    statuses.add(status);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
        // crédito de fundo (1) + transferência vencedora (2) = 3; e exatamente 1 DEBIT saiu da origem
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(3);
        long sourceDebits = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT && e.getAccount().getId().equals(source.getId()))
                .count();
        assertThat(sourceDebits).isEqualTo(1); // sem duplo débito
        Account a = accountRepository.findById(destinationA.getId()).orElseThrow();
        Account b = accountRepository.findById(destinationB.getId()).orElseThrow();
        assertThat(a.getBalance().add(b.getBalance())).isEqualByComparingTo("80.00");
    }
}