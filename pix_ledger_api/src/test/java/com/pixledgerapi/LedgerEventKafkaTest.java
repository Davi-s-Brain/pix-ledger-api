package com.pixledgerapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixledgerapi.event.EntryCreatedEvent;
import com.pixledgerapi.event.LedgerEventPublisher;
import com.pixledgerapi.model.Account;
import com.pixledgerapi.model.EntryEvent;
import com.pixledgerapi.model.EntryType;
import com.pixledgerapi.repository.AccountRepository;
import com.pixledgerapi.repository.EntryEventRepository;
import com.pixledgerapi.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integração Kafka: producer (AFTER_COMMIT) -> broker -> consumer -> projeção entry_events.
 * O consumer é assíncrono, então as asserções esperam a linha aparecer (poll com deadline).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LedgerEventKafkaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private EntryEventRepository entryEventRepository;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        entryEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private String bearerToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }

    /**
     * E2E: POST cria lançamento -> commit -> evento no Kafka -> consumer grava a projeção.
     */
    @Test
    void createEntry_publishesEvent_consumerPersistsProjection() throws Exception {
        Account account = new Account();
        account.setOwnerName("Kafka");
        account.setStatus(Account.Status.ACTIVE);
        account = accountRepository.save(account);

        String response = mockMvc.perform(post("/api/v1/accounts/{id}/entries", account.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryType\":\"CREDIT\",\"amount\":100.00,\"description\":\"deposito\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode entryId = objectMapper.readTree(response).get("entry").get("id");
        assertThat(entryId).isNotNull();

        List<EntryEvent> projections = awaitProjections(1);
        EntryEvent projection = projections.get(0);
        assertThat(projection.getEntryId()).isEqualTo(UUID.fromString(entryId.asText()));
        assertThat(projection.getAccountId()).isEqualTo(account.getId());
        assertThat(projection.getEntryType()).isEqualTo(EntryType.CREDIT);
        assertThat(projection.getAmount()).isEqualByComparingTo("100.00");
        assertThat(projection.getDescription()).isEqualTo("deposito");
    }

    /**
     * Idempotência: o Kafka entrega pelo menos uma vez; o mesmo evento enviado 2x
     * (redelivery/restart) não pode gerar linha duplicada (PK = entry_id).
     */
    @Test
    void duplicateEvent_isNotPersistedTwice() throws Exception {
        EntryCreatedEvent event = new EntryCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), EntryType.DEBIT,
                new BigDecimal("50.00"), "dup", Instant.now());

        kafkaTemplate.send(LedgerEventPublisher.TOPIC, event.entryId().toString(), event);
        kafkaTemplate.send(LedgerEventPublisher.TOPIC, event.entryId().toString(), event);

        assertThat(awaitProjections(1)).hasSize(1);
    }

    /**
     * A transferência é 1 commit com 2 lançamentos -> 2 eventos no Kafka -> 2 projeções.
     */
    @Test
    void transfer_publishesBothEntryEvents() throws Exception {
        Account source = accountRepository.save(account("src"));
        Account destination = accountRepository.save(account("dst"));

        // credita a origem (1º lançamento -> 1º evento)
        mockMvc.perform(post("/api/v1/accounts/{id}/entries", source.getId())
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryType\":\"CREDIT\",\"amount\":100.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + source.getId()
                                + "\",\"destinationAccountId\":\"" + destination.getId()
                                + "\",\"amount\":60.00}"))
                .andExpect(status().isCreated());

        List<EntryEvent> projections = awaitProjections(3); // credit + debit + credit
        assertThat(projections)
                .anyMatch(p -> p.getEntryType() == EntryType.DEBIT
                        && p.getAccountId().equals(source.getId())
                        && p.getAmount().compareTo(new BigDecimal("60.00")) == 0);
        assertThat(projections)
                .anyMatch(p -> p.getEntryType() == EntryType.CREDIT
                        && p.getAccountId().equals(destination.getId())
                        && p.getAmount().compareTo(new BigDecimal("60.00")) == 0);
    }

    private Account account(String owner) {
        Account account = new Account();
        account.setOwnerName(owner);
        account.setStatus(Account.Status.ACTIVE);
        return account;
    }

    private List<EntryEvent> awaitProjections(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        List<EntryEvent> projections;
        do {
            projections = entryEventRepository.findAll();
            if (projections.size() >= expected) {
                return projections;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return projections;
    }
}