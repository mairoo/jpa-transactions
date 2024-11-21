package kr.co.pincoin.jpa.service;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import kr.co.pincoin.jpa.entity.Balance;
import kr.co.pincoin.jpa.entity.Transaction;
import kr.co.pincoin.jpa.exception.BalanceProcessingException;
import kr.co.pincoin.jpa.repository.BalanceRepository;
import kr.co.pincoin.jpa.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResilientTransactionServiceTest {
    private static final Long BALANCE_ID = 1L;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

    private static final String TX_ID = "tx-123";

    private static final String TRANSACTION_TOKEN = "token-123";

    @InjectMocks
    private ResilientTransactionService resilientTransactionService;

    @Mock
    private BalanceRepository balanceRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private Balance balance;

    @BeforeEach
    void setUp() {
        balance = Balance.createWithInitialBalance(INITIAL_BALANCE);
    }

    @Test
    @DisplayName("비관적 락 - 정상 처리 케이스")
    void processWithPessimisticLock_Success() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByIdWithPessimisticLock(BALANCE_ID))
                .willReturn(Optional.of(balance));
        given(balanceRepository.save(any(Balance.class))).willReturn(balance);
        given(transactionRepository.save(any(Transaction.class))).willReturn(Transaction.create(new BigDecimal("100")));

        // when
        boolean result = resilientTransactionService
                .processWithPessimisticLockAndRetry(BALANCE_ID, new BigDecimal("100"), TX_ID);

        // then
        assertThat(result).isTrue();
        verify(balanceRepository, times(1)).findByIdWithPessimisticLock(BALANCE_ID);
        verify(balanceRepository, times(1)).save(any(Balance.class));
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("비관적 락 - 이미 처리된 거래")
    void processWithPessimisticLock_AlreadyProcessed() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(true);

        // when
        boolean result = resilientTransactionService
                .processWithPessimisticLockAndRetry(BALANCE_ID, new BigDecimal("100"), TX_ID);

        // then
        assertThat(result).isFalse();
        verify(balanceRepository, never()).findByIdWithPessimisticLock(any());
        verify(balanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("비관적 락 - 재시도 후 성공")
    void processWithPessimisticLock_RetrySuccess() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByIdWithPessimisticLock(BALANCE_ID))
                .willThrow(PessimisticLockException.class)
                .willReturn(Optional.of(balance));
        given(balanceRepository.save(any(Balance.class))).willReturn(balance);

        // when
        boolean result = resilientTransactionService
                .processWithPessimisticLockAndRetry(BALANCE_ID, new BigDecimal("100"), TX_ID);

        // then
        assertThat(result).isTrue();
        verify(balanceRepository, times(2)).findByIdWithPessimisticLock(BALANCE_ID);
    }

    @Test
    @DisplayName("낙관적 락 - 정상 처리 케이스")
    void processWithOptimisticLock_Success() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByIdWithOptimisticLock(BALANCE_ID))
                .willReturn(Optional.of(balance));
        given(balanceRepository.save(any(Balance.class))).willReturn(balance);

        // when
        boolean result = resilientTransactionService
                .processWithOptimisticLockAndRetry(BALANCE_ID, new BigDecimal("100"), TX_ID);

        // then
        assertThat(result).isTrue();
        verify(balanceRepository, times(1)).findByIdWithOptimisticLock(BALANCE_ID);
    }

    @Test
    @DisplayName("낙관적 락 - 최대 재시도 초과")
    void processWithOptimisticLock_MaxRetriesExceeded() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByIdWithOptimisticLock(BALANCE_ID))
                .willThrow(OptimisticLockException.class);

        // when & then
        assertThatThrownBy(() ->
                                   resilientTransactionService.processWithOptimisticLockAndRetry(BALANCE_ID,
                                                                                                 new BigDecimal("100"),
                                                                                                 TX_ID))
                .isInstanceOf(BalanceProcessingException.class)
                .hasMessageContaining("낙관적 락 처리 실패");
    }

    @Test
    @DisplayName("유니크 제약 - 정상 처리 케이스")
    void processWithUniqueConstraint_Success() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByTransactionToken(TRANSACTION_TOKEN))
                .willReturn(Optional.empty());
        given(balanceRepository.findById(BALANCE_ID)).willReturn(Optional.of(balance));
        given(balanceRepository.save(any(Balance.class))).willReturn(balance);

        // when
        boolean result = resilientTransactionService
                .processWithUniqueConstraintAndRetry(BALANCE_ID, new BigDecimal("100"), TX_ID, TRANSACTION_TOKEN);

        // then
        assertThat(result).isTrue();
        verify(balanceRepository, times(1)).findById(BALANCE_ID);
        verify(balanceRepository, times(1)).save(any(Balance.class));
    }

    @Test
    @DisplayName("유니크 제약 - 중복 토큰")
    void processWithUniqueConstraint_DuplicateToken() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByTransactionToken(TRANSACTION_TOKEN))
                .willReturn(Optional.of(balance));

        // when
        boolean result = resilientTransactionService
                .processWithUniqueConstraintAndRetry(BALANCE_ID, new BigDecimal("100"), TX_ID, TRANSACTION_TOKEN);

        // then
        assertThat(result).isFalse();
        verify(balanceRepository, never()).findById(any());
        verify(balanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("잔액 부족 예외 처리")
    void processWithInsufficientBalance() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByIdWithPessimisticLock(BALANCE_ID))
                .willReturn(Optional.of(balance));

        // when & then
        assertThatThrownBy(() ->
                                   resilientTransactionService.processWithPessimisticLockAndRetry(BALANCE_ID,
                                                                                                  new BigDecimal("-2000"),
                                                                                                  TX_ID))
                .isInstanceOf(BalanceProcessingException.class)
                .hasMessageContaining("잔액 부족");
    }

    @Test
    @DisplayName("존재하지 않는 잔액 ID 예외 처리")
    void processWithNonExistentBalance() {
        // given
        given(transactionRepository.existsByTxId(TX_ID)).willReturn(false);
        given(balanceRepository.findByIdWithPessimisticLock(BALANCE_ID))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                                   resilientTransactionService.processWithPessimisticLockAndRetry(BALANCE_ID,
                                                                                                  new BigDecimal("100"),
                                                                                                  TX_ID))
                .isInstanceOf(BalanceProcessingException.class)
                .hasMessageContaining("잔액을 찾을 수 없음");
    }
}