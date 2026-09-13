package com.pixledgerapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixledgerapi.model.Account;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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
        Account account = new Account();
        account.setOwnerName("Davi");
        account = accountRepository.save(account);

        for (int i = 0; i < 3; i++) {
            LedgerEntry entry = new LedgerEntry();
            entry.setAccount(account);
            entry.setEntryType(LedgerEntry.EntryType.CREDIT);
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
}