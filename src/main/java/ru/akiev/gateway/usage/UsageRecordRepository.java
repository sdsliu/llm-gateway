package ru.akiev.gateway.usage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    List<UsageRecord> findByCreatedAtAfterOrderByCreatedAtAsc(Instant from);

    List<UsageRecord> findTop20ByOrderByCreatedAtDescIdDesc();

    /**
     * Сумма токенов по провайдерам с момента from: [provider, sum]. Для восстановления суточных и месячных
     * счётчиков после рестарта (issue #34). Агрегат в SQL — чтобы не тянуть в память записи за весь месяц.
     */
    @Query("select r.provider, coalesce(sum(r.totalTokens), 0) from UsageRecord r "
            + "where r.createdAt >= :from group by r.provider")
    List<Object[]> sumTokensByProviderSince(@Param("from") Instant from);
}
