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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AccountControllerTest {

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
        JsonNode json = objectMapper.readTree(body);
        return "Bearer " + json.get("accessToken").asText();
    }

    private Account newAccount(String owner, Account.Status status) {
        Account account = new Account();
        account.setOwnerName(owner);
        account.setStatus(status);
        return accountRepository.save(account);
    }

    private String entryBody(EntryType type, String amount) {
        return "{\"entryType\":\"" + type + "\",\"amount\":" + amount + "}";
    }

    @BeforeEach
    void cleanDb() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void createAccount_persistsAndReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Davi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Davi"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0));

        Account saved = accountRepository.findAll().get(0);
        assertThat(saved.getOwnerName()).isEqualTo("Davi");
        assertThat(saved.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void createAccount_invalidPayload_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getLedger_returnsPagedEntries() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);

        for (int i = 0; i < 3; i++) {
            LedgerEntry entry = new LedgerEntry();
            entry.setAccount(account);
            entry.setEntryType(EntryType.CREDIT);
            entry.setAmount(BigDecimal.TEN);
            ledgerEntryRepository.save(entry);
        }

        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", account.getId())
                        .header("Authorization", bearerToken())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.numberOfElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getLedger_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", UUID.randomUUID())
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createEntry_credit_increasesBalance() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(EntryType.CREDIT, "100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.entryType").value("CREDIT"))
                .andExpect(jsonPath("$.entry.amount").value(100.00))
                .andExpect(jsonPath("$.balance").value(100.00));

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(ledgerEntryRepository.count()).isEqualTo(1);
    }

    @Test
    void createEntry_debit_decreasesBalance() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);
        account.setBalance(new BigDecimal("100.00"));
        accountRepository.save(account);

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(EntryType.DEBIT, "40.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.entryType").value("DEBIT"))
                .andExpect(jsonPath("$.balance").value(60.00));

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("60.00");
    }

    @Test
    void createEntry_debit_insufficientFunds_returns422() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(EntryType.DEBIT, "10.00")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void createEntry_debit_exactBalance_isAllowed() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);
        account.setBalance(new BigDecimal("50.00"));
        accountRepository.save(account);

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(EntryType.DEBIT, "50.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0));

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void createEntry_unknownAccount_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/entries", UUID.randomUUID())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(EntryType.CREDIT, "10.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createEntry_inactiveAccount_returns409() throws Exception {
        Account account = newAccount("Davi", Account.Status.BLOCKED);

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(EntryType.CREDIT, "10.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void createEntry_invalidPayload_returns400() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryType\":\"CREDIT\",\"amount\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createEntry_concurrentDebits_onlyOneSucceeds() throws Exception {
        Account account = newAccount("Davi", Account.Status.ACTIVE);
        account.setBalance(new BigDecimal("100.00"));
        accountRepository.save(account);
        UUID accountId = account.getId();

        List<Integer> statuses = postDebitsConcurrently(accountId, "80.00", 2);

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("20.00");
        assertThat(ledgerEntryRepository.count()).isEqualTo(1);
    }

    @Test
    void createEntry_concurrentDebits_bothSucceedWithRetry() throws Exception {
        // saldo 80, dois débitos de 40 em paralelo: o perdedor do lock
        // re-lê o saldo (40 restante) e consegue completar no retry
        Account account = newAccount("Davi", Account.Status.ACTIVE);
        account.setBalance(new BigDecimal("80.00"));
        accountRepository.save(account);
        UUID accountId = account.getId();

        List<Integer> statuses = postDebitsConcurrently(accountId, "40.00", 2);

        assertThat(statuses).containsExactlyInAnyOrder(201, 201);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    private List<Integer> postDebitsConcurrently(UUID accountId, String amount, int threads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> statuses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    statuses.add(String.valueOf(mockMvc.perform(post("/api/v1/accounts/{id}/entries", accountId)
                                    .header("Authorization", bearerToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(entryBody(EntryType.DEBIT, amount)))
                            .andReturn().getResponse().getStatus()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        return statuses.stream().map(Integer::parseInt).collect(Collectors.toList());
    }
}