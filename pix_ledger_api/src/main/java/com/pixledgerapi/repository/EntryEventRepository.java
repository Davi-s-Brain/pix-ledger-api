package com.pixledgerapi.repository;

import com.pixledgerapi.model.EntryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EntryEventRepository extends JpaRepository<EntryEvent, UUID> {
}