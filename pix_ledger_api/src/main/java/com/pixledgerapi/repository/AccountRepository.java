package com.pixledgerapi.repository;

import com.pixledgerapi.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Trava as contas com SELECT ... FOR UPDATE na ordem de UUID (determinística).
     * Duas transferências concorrentes que travam contas em ordem crescente de id
     * nunca entram em deadlock, e o lock serializa o acesso ao saldo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdForUpdate(@Param("ids") List<UUID> ids);
}