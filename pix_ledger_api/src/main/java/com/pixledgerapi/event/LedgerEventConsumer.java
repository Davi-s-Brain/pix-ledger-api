package com.pixledgerapi.event;

import com.pixledgerapi.model.EntryEvent;
import com.pixledgerapi.repository.EntryEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer idempotente: grava a projeção/auditoria de cada lançamento.
 * O Kafka entrega "pelo menos uma vez", então o mesmo evento pode chegar 2x
 * (redelivery, restart) — o PK = entry_id garante que duplicado vira no-op.
 */
@Component
public class LedgerEventConsumer {

    @Autowired
    private EntryEventRepository entryEventRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @KafkaListener(topics = LedgerEventPublisher.TOPIC, groupId = "ledger-projector")
    public void onEntryCreated(EntryCreatedEvent event) {
        if (entryEventRepository.existsById(event.entryId())) {
            meterRegistry.counter("ledger.event.duplicated").increment();
            return;
        }

        EntryEvent projection = new EntryEvent();
        projection.setEntryId(event.entryId());
        projection.setAccountId(event.accountId());
        projection.setEntryType(event.entryType());
        projection.setAmount(event.amount());
        projection.setDescription(event.description());
        projection.setOccurredAt(event.createdAt());
        entryEventRepository.save(projection);

        meterRegistry.counter("ledger.event.consumed", "type", event.entryType().name()).increment();
    }
}