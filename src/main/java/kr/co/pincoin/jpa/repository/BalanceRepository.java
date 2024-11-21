package kr.co.pincoin.jpa.repository;

import jakarta.persistence.LockModeType;
import kr.co.pincoin.jpa.entity.Balance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BalanceRepository extends JpaRepository<Balance, Long> {
    // 비관적 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Balance b WHERE b.id = :id")
    Optional<Balance> findByIdWithPessimisticLock(@Param("id") Long id);

    // 낙관적 락 - 명시적 쿼리 작성(@Version 애노테이션 때문에 불필요)
    @Lock(LockModeType.OPTIMISTIC)
    @Query("SELECT b FROM Balance b WHERE b.id = :id")
    Optional<Balance> findByIdWithOptimisticLock(@Param("id") Long id);

    // 유니크 제약 기법
    Optional<Balance> findByTransactionToken(String transactionToken);
}
