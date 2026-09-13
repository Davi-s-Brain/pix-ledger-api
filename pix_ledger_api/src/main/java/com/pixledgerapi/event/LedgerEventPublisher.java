package com.pixledgerapi.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica o evento no Kafka SOMENTE depois do commit da transação.
 * Se a transação der rollback, o evento nem chega ao Kafka (nada de "evento de operação que não existiu").
 */
@Component
public class LedgerEventPublisher {

    public static final String TOPIC = "ledger.entry.created";

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEntryCreated(EntryCreatedEvent event) {
        kafkaTemplate.send(TOPIC, event.entryId().toString(), event);
    }
}