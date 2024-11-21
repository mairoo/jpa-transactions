package kr.co.pincoin.jpa.service;

import kr.co.pincoin.jpa.entity.Balance;
import kr.co.pincoin.jpa.repository.BalanceRepository;
import kr.co.pincoin.jpa.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class IdempotentTransactionServiceTest {
    @Autowired
    private IdempotentTransactionService idempotentTransactionService;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Balance testBalance;

    @BeforeEach
    void setUp() {
        balanceRepository.deleteAll();
        transactionRepository.deleteAll();
        testBalance = balanceRepository.save(Balance.createWithInitialBalance(new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("비관적 락: 동일 txid로 중복 요청시 한번만 처리되어야 함")
    void pessimisticLockWithDuplicateRequest() {
        String txid = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("100.00");

        // 첫 번째 요청
        boolean firstResult = idempotentTransactionService.processWithPessimisticLock(
                testBalance.getId(),
                amount,
                txid
                                                                                     );

        // 동일 txid로 두 번째 요청
        boolean secondResult = idempotentTransactionService.processWithPessimisticLock(
                testBalance.getId(),
                amount,
                txid
                                                                                      );

        assertThat(firstResult).isTrue()
                .as("첫 번째 요청은 성공해야 함");
        assertThat(secondResult).isFalse()
                .as("동일 txid의 두 번째 요청은 실패해야 함");

        Balance updatedBalance = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updatedBalance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1100.00"))
                .as("잔액이 한 번만 증가해야 함");
    }

    @Test
    @DisplayName("비관적 락: 서로 다른 txid로 동시 요청시 모두 처리되어야 함")
    void pessimisticLockWithConcurrentRequests() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        // 두 개의 서로 다른 txid로 동시에 요청
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    idempotentTransactionService.processWithPessimisticLock(
                            testBalance.getId(),
                            new BigDecimal("100.00"),
                            UUID.randomUUID().toString()
                                                                           );
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        Balance updatedBalance = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updatedBalance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1200.00"))
                .as("두 요청 모두 처리되어 잔액이 200원 증가해야 함");
    }

    @Test
    @DisplayName("낙관적 락: 동일 txid로 중복 요청시 한번만 처리되어야 함")
    void optimisticLockWithDuplicateRequest() {
        String txid = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("100.00");

        // 첫 번째 요청
        boolean firstResult = idempotentTransactionService.processWithOptimisticLock(
                testBalance.getId(),
                amount,
                txid
                                                                                    );

        // 동일 txid로 두 번째 요청
        boolean secondResult = idempotentTransactionService.processWithOptimisticLock(
                testBalance.getId(),
                amount,
                txid
                                                                                     );

        assertThat(firstResult).isTrue()
                .as("첫 번째 요청은 성공해야 함");
        assertThat(secondResult).isFalse()
                .as("동일 txid의 두 번째 요청은 실패해야 함");

        Balance updatedBalance = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updatedBalance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1100.00"))
                .as("잔액이 한 번만 증가해야 함");
    }

    @Test
    @DisplayName("낙관적 락: 서로 다른 txid로 동시 요청시 버전 충돌이 발생해야 함")
    void optimisticLockWithConcurrentRequests() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 두 개의 서로 다른 txid로 동시에 요청
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    idempotentTransactionService.processWithOptimisticLock(
                            testBalance.getId(),
                            new BigDecimal("100.00"),
                            UUID.randomUUID().toString()
                                                                          );
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(successCount.get()).isEqualTo(1)
                .as("하나의 요청만 성공해야 함");
        assertThat(failCount.get()).isEqualTo(1)
                .as("하나의 요청은 실패해야 함");

        Balance updatedBalance = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updatedBalance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1100.00"))
                .as("하나의 요청만 성공하여 잔액이 100원만 증가해야 함");
    }

    @Test
    @DisplayName("유니크 제약: 동일 txid와 토큰으로 중복 요청시 한번만 처리되어야 함")
    void uniqueConstraintWithDuplicateRequest() {
        String txid = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("100.00");

        // 첫 번째 요청
        boolean firstResult = idempotentTransactionService.processWithUniqueConstraint(
                testBalance.getId(),
                amount,
                txid,
                token
                                                                                      );

        // 동일 txid와 토큰으로 두 번째 요청
        boolean secondResult = idempotentTransactionService.processWithUniqueConstraint(
                testBalance.getId(),
                amount,
                txid,
                token
                                                                                       );

        assertThat(firstResult).isTrue()
                .as("첫 번째 요청은 성공해야 함");
        assertThat(secondResult).isFalse()
                .as("동일 정보의 두 번째 요청은 실패해야 함");

        Balance updatedBalance = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updatedBalance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1100.00"))
                .as("잔액이 한 번만 증가해야 함");
    }

    @Test
    @DisplayName("잔액 부족 시 예외가 발생해야 함")
    void insufficientBalanceTest() {
        String txid = UUID.randomUUID().toString();

        assertThatThrownBy(() ->
                                   idempotentTransactionService.processWithPessimisticLock(
                                           testBalance.getId(),
                                           new BigDecimal("-2000.00"),
                                           txid
                                                                                          )
                          )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잔액이 부족합니다");
    }
}