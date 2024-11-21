package kr.co.pincoin.jpa.service;

import kr.co.pincoin.jpa.entity.Balance;
import kr.co.pincoin.jpa.repository.BalanceRepository;
import kr.co.pincoin.jpa.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
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
class IntegrityBalanceServiceTest {
    @Autowired
    private IntegrityBalanceService integrityBalanceService;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Balance testBalance;

    @BeforeEach
    void setUp() {
        balanceRepository.deleteAll();
        transactionRepository.deleteAll();
        testBalance = integrityBalanceService.createBalance(new BigDecimal("1000.00"));
    }

    @Test
    void pessimisticLockTest() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        // 동시에 2개의 스레드에서 각각 100원씩 입금 시도
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    integrityBalanceService.updateBalanceWithPessimisticLock(
                            testBalance.getId(),
                            new BigDecimal("100.00")
                                                                            );
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 스레드 완료 대기
        latch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        // 비관적 락으로 인해 모든 트랜잭션이 순차적으로 처리되어 정확히 200원이 증가해야 함
        Balance updated = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updated.getBalance())
                .isEqualByComparingTo(new BigDecimal("1200.00"))
                .as("비관적 락을 통해 동시 입금이 정상적으로 처리되어야 함");
    }

    @Test
    void optimisticLockTest() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // 동시에 2개의 스레드에서 각각 100원씩 입금 시도
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // 동시 시작을 위한 대기
                    integrityBalanceService.updateBalanceWithOptimisticLock(testBalance.getId(),
                                                                            new BigDecimal("100.00"));
                } catch (ObjectOptimisticLockingFailureException e) {
                    exceptionCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 모든 스레드 동시 시작
        endLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        // 낙관적 락으로 인해 적어도 하나의 트랜잭션은 실패해야 함
        assertThat(exceptionCount.get())
                .isPositive()
                .as("낙관적 락으로 인해 동시 업데이트 시 충돌이 발생해야 함");

        Balance updated = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updated.getBalance())
                .isEqualByComparingTo(new BigDecimal("1100.00"))
                .as("하나의 트랜잭션만 성공적으로 처리되어야 함");
    }

    @Test
    void uniqueConstraintTest() {
        String token = UUID.randomUUID().toString();

        // 첫 번째 트랜잭션 시도
        boolean firstResult = integrityBalanceService.updateBalanceWithUniqueConstraint(testBalance.getId(),
                                                                                        new BigDecimal("100.00"),
                                                                                        token);

        // 동일 토큰으로 두 번째 트랜잭션 시도
        boolean secondResult = integrityBalanceService.updateBalanceWithUniqueConstraint(testBalance.getId(),
                                                                                         new BigDecimal("100.00"),
                                                                                         token);

        assertThat(firstResult)
                .isTrue()
                .as("첫 번째 트랜잭션은 성공해야 함");
        assertThat(secondResult)
                .isFalse()
                .as("동일 토큰으로 인해 두 번째 트랜잭션은 실패해야 함");

        Balance updated = balanceRepository.findById(testBalance.getId()).orElseThrow();
        assertThat(updated.getBalance())
                .isEqualByComparingTo(new BigDecimal("1100.00"))
                .as("중복 처리 방지로 한 번만 잔액이 증가해야 함");
    }

    @Test
    void insufficientBalanceTest() {
        assertThatThrownBy(() -> integrityBalanceService.updateBalanceWithPessimisticLock(testBalance.getId(),
                                                                                          new BigDecimal("-2000.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잔액이 부족합니다")
                .as("잔액이 부족한 경우 출금이 실패해야 함");
    }
}