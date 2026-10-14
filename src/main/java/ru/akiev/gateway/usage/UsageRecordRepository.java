package ru.akiev.gateway.usage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    List<UsageRecord> findByCreatedAtAfterOrderByCreatedAtAsc(Instant from);

    List<UsageRecord> findTop20ByOrderByCreatedAtDescIdDesc();
}
