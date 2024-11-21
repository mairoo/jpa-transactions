package kr.co.pincoin.jpa.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import kr.co.pincoin.jpa.entity.Balance;
import kr.co.pincoin.jpa.entity.Transaction;
import kr.co.pincoin.jpa.exception.BalanceProcessingException;
import kr.co.pincoin.jpa.repository.BalanceRepository;
import kr.co.pincoin.jpa.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientBalanceService {
    // 재시도 관련 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000; // 1초
    private static final long MAX_RETRY_DELAY_MS = 5000; // 5초
    private final BalanceRepository balanceRepository;
    private final TransactionRepository transactionRepository;

    /**
     * 비관적 락을 사용한 잔액 변경 with 지수 백오프(exponential backoff) 재시도
     * - 실패할 때마다 대기 시간을 2배씩 증가
     * - 데이터베이스 부하를 고려한 전략
     */
    @Transactional
    public boolean processWithPessimisticLockAndRetry(Long balanceId, BigDecimal amount, String txId) {
        for (int attempts = 0; attempts < MAX_RETRY_ATTEMPTS; attempts++) {
            try {
                // 이미 처리된 거래인지 확인
                if (transactionRepository.existsByTxId(txId)) {
                    return false;
                }

                // 비관적 락으로 잔액 정보 조회
                Balance balance = balanceRepository.findByIdWithPessimisticLock(balanceId)
                        .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

                // 잔액 변경 및 거래 이력 생성
                balance.changeBalance(amount);
                Transaction transaction = Transaction.create(amount);
                transaction.updateTxId(txId);

                // 변경사항 저장
                balanceRepository.save(balance);
                transactionRepository.save(transaction);

                return true;

            } catch (PessimisticLockException | LockTimeoutException e) {
                if (attempts >= MAX_RETRY_ATTEMPTS - 1) {
                    log.error("비관적 락 획득 실패 - 최대 재시도 횟수 초과. balanceId: {}", balanceId);
                    throw new BalanceProcessingException("비관적 락 처리 실패", e);
                }

                long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (1L << attempts), MAX_RETRY_DELAY_MS);
                log.warn("비관적 락 획득 실패 - 재시도 {}/{}. balanceId: {}. {}ms 후 재시도...",
                         attempts + 1, MAX_RETRY_ATTEMPTS, balanceId, delayMs);

                sleep(delayMs);
            } catch (Exception e) {
                handleException("비관적 락 처리", e, balanceId);
                return false;
            }
        }
        return false;
    }

    /**
     * 낙관적 락을 사용한 잔액 변경 with 선형 백오프(linear backoff) 재시도
     * - 실패할 때마다 대기 시간을 일정하게 증가
     * - 충돌이 덜 심각한 상황에 적합
     */
    @Transactional
    public boolean processWithOptimisticLockAndRetry(Long balanceId, BigDecimal amount, String txId) {
        for (int attempts = 0; attempts < MAX_RETRY_ATTEMPTS; attempts++) {
            try {
                // 이미 처리된 거래인지 확인
                if (transactionRepository.existsByTxId(txId)) {
                    return false;
                }

                // 낙관적 락으로 잔액 정보 조회
                Balance balance = balanceRepository.findByIdWithOptimisticLock(balanceId)
                        .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

                // 잔액 변경 및 거래 이력 생성
                balance.changeBalance(amount);
                Transaction transaction = Transaction.create(amount);
                transaction.updateTxId(txId);

                // 변경사항 저장
                balanceRepository.save(balance);
                transactionRepository.save(transaction);

                return true;

            } catch (OptimisticLockException e) {
                if (attempts >= MAX_RETRY_ATTEMPTS - 1) {
                    log.error("낙관적 락 충돌 - 최대 재시도 횟수 초과. balanceId: {}", balanceId);
                    throw new BalanceProcessingException("낙관적 락 처리 실패", e);
                }

                long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (attempts + 1), MAX_RETRY_DELAY_MS);
                log.warn("낙관적 락 충돌 - 재시도 {}/{}. balanceId: {}. {}ms 후 재시도...",
                         attempts + 1, MAX_RETRY_ATTEMPTS, balanceId, delayMs);

                sleep(delayMs);
            } catch (Exception e) {
                handleException("낙관적 락 처리", e, balanceId);
                return false;
            }
        }
        return false;
    }

    /**
     * 유니크 제약 조건을 사용한 잔액 변경 with 단순 재시도
     * - 고정된 대기 시간으로 재시도
     * - 중복 처리는 실패로 처리하고 종료
     */
    @Transactional
    public boolean processWithUniqueConstraintAndRetry(Long balanceId, BigDecimal amount,
                                                       String txId, String transactionToken) {
        for (int attempts = 0; attempts < MAX_RETRY_ATTEMPTS; attempts++) {
            try {
                // 이미 처리된 거래인지 확인
                if (transactionRepository.existsByTxId(txId)) {
                    return false;
                }

                // 이미 처리된 토큰인지 확인
                if (balanceRepository.findByTransactionToken(transactionToken).isPresent()) {
                    return false;
                }

                // 잔액 정보 조회
                Balance balance = balanceRepository.findById(balanceId)
                        .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

                // 잔액 변경 및 거래 이력 생성
                balance.changeBalance(amount);
                Transaction transaction = Transaction.create(amount);
                transaction.updateTxId(txId);

                // 트랜잭션 토큰 설정
                balance.updateTransactionToken(transactionToken);

                // 변경사항 저장
                balanceRepository.save(balance);
                transactionRepository.save(transaction);

                return true;

            } catch (DataIntegrityViolationException e) {
                log.warn("중복 트랜잭션 감지됨. token: {}", transactionToken);
                return false;
            } catch (Exception e) {
                if (attempts >= MAX_RETRY_ATTEMPTS - 1) {
                    handleException("유니크 제약 조건 처리", e, balanceId);
                    return false;
                }

                log.warn("유니크 제약 조건 처리 실패 - 재시도 {}/{}. balanceId: {}. {}ms 후 재시도...",
                         attempts + 1, MAX_RETRY_ATTEMPTS, balanceId, INITIAL_RETRY_DELAY_MS);

                sleep(INITIAL_RETRY_DELAY_MS);
            }
        }
        return false;
    }

    /**
     * 예외 처리 및 로깅을 위한 헬퍼 메서드
     */
    private void handleException(String operation, Exception e, Long balanceId) {
        switch (e) {
            case EntityNotFoundException _ -> {
                log.error("잔액 정보를 찾을 수 없음 - {} 처리 중 balanceId: {}", operation, balanceId);
                throw new BalanceProcessingException("잔액을 찾을 수 없음: " + balanceId, e);
            }
            case IllegalStateException _ -> {
                log.error("잔액 부족 - {} 처리 중 balanceId: {}", operation, balanceId);
                throw new BalanceProcessingException("잔액 부족", e);
            }
            case IllegalArgumentException _ -> {
                log.error("잘못된 인자 - {} 처리 중 balanceId: {}", operation, balanceId);
                throw new BalanceProcessingException("잘못된 인자", e);
            }
            case null, default -> {
                log.error("예상치 못한 오류 발생 - {} 처리 중 balanceId: {}", operation, balanceId, e);
                throw new BalanceProcessingException("잔액 처리 중 예상치 못한 오류 발생", e);
            }
        }
    }

    private void sleep(long milliseconds) {
        try {
            TimeUnit.MILLISECONDS.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BalanceProcessingException("재시도 중 인터럽트 발생", e);
        }
    }
}