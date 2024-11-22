package kr.co.pincoin.jpa.service;

import jakarta.persistence.EntityNotFoundException;
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
public class ResilientTransactionPreRegistryService {
    // 재시도 관련 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private static final long INITIAL_RETRY_DELAY_MS = 1000; // 1초

    private static final long MAX_RETRY_DELAY_MS = 5000; // 5초

    private final BalanceRepository balanceRepository;

    private final TransactionRepository transactionRepository;

    /**
     * 트랜잭션 처리 프로세스:
     * 1. 트랜잭션 선등록으로 멱등성 보장 (unique constraint 활용)
     * 2. 잔액 처리 (비관적 락 활용)
     * <p>
     * 일반적인 흐름과 다른 이유:
     * - exists 쿼리 제거로 성능 최적화
     * - DB 제약조건을 활용한 안전한 멱등성 보장
     * - Race condition 원천 방지
     */
    @Transactional
    public boolean processWithPessimisticLockAndRetry(Long balanceId, BigDecimal amount, String txId) {
        int attempt = 0;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                // 트랜잭션 선등록 시도 (멱등성 체크)
                Transaction transaction = Transaction.create(amount);
                transaction.updateTxId(txId);
                transactionRepository.save(transaction);

                // 비관적 락으로 잔액 조회 및 처리
                Balance balance = balanceRepository.findByIdWithPessimisticLock(balanceId)
                        .orElseThrow(() -> new BalanceProcessingException("잔액을 찾을 수 없음: " + balanceId));

                balance.changeBalance(amount);
                balanceRepository.save(balance);

                return true;

            } catch (DataIntegrityViolationException e) {
                return false; // 이미 처리된 요청
            } catch (PessimisticLockException e) {
                if (attempt >= MAX_RETRY_ATTEMPTS - 1) {
                    log.error("비관적 락 획득 실패 - 최대 재시도 횟수 초과. balanceId: {}", balanceId);
                    throw new BalanceProcessingException("비관적 락 처리 실패", e);
                }

                // 지수 백오프 적용
                long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (1L << attempt), MAX_RETRY_DELAY_MS);
                log.warn("비관적 락 획득 실패 - 재시도 {}/{}. balanceId: {}. {}ms 후 재시도...",
                         attempt + 1, MAX_RETRY_ATTEMPTS, balanceId, delayMs);

                sleep(delayMs);
                attempt++;
            } catch (Exception e) {
                handleException("비관적 락 처리", e, balanceId);
                return false;
            }
        }
        return false;
    }

    /**
     * 트랜잭션 처리 프로세스:
     * 1. 트랜잭션 선등록으로 멱등성 보장 (unique constraint 활용)
     * 2. 잔액 처리 (낙관적 락 활용)
     * <p>
     * 일반적인 흐름과 다른 이유:
     * - exists 쿼리 제거로 성능 최적화
     * - DB 제약조건을 활용한 안전한 멱등성 보장
     * - Race condition 원천 방지
     */
    @Transactional
    public boolean processWithOptimisticLockAndRetry(Long balanceId, BigDecimal amount, String txId) {
        int attempt = 0;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                // 트랜잭션 선등록 시도 (멱등성 체크)
                Transaction transaction = Transaction.create(amount);
                transaction.updateTxId(txId);
                transactionRepository.save(transaction);

                // 낙관적 락으로 잔액 조회 및 처리
                Balance balance = balanceRepository.findByIdWithOptimisticLock(balanceId)
                        .orElseThrow(() -> new BalanceProcessingException("잔액을 찾을 수 없음: " + balanceId));

                balance.changeBalance(amount);
                balanceRepository.save(balance);

                return true;

            } catch (DataIntegrityViolationException e) {
                return false; // 이미 처리된 요청
            } catch (OptimisticLockException e) {
                if (attempt >= MAX_RETRY_ATTEMPTS - 1) {
                    log.error("낙관적 락 충돌 - 최대 재시도 횟수 초과. balanceId: {}", balanceId);
                    throw new BalanceProcessingException("낙관적 락 처리 실패", e);
                }

                // 선형 백오프 적용
                long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (attempt + 1), MAX_RETRY_DELAY_MS);
                log.warn("낙관적 락 충돌 - 재시도 {}/{}. balanceId: {}. {}ms 후 재시도...",
                         attempt + 1, MAX_RETRY_ATTEMPTS, balanceId, delayMs);

                sleep(delayMs);
                attempt++;
            } catch (Exception e) {
                handleException("낙관적 락 처리", e, balanceId);
                return false;
            }
        }
        return false;
    }

    /**
     * 트랜잭션 처리 프로세스:
     * 1. 트랜잭션 선등록으로 멱등성 보장 (unique constraint 활용)
     * 2. 잔액 처리 (유니크 제약 조건 활용)
     * <p>
     * 일반적인 흐름과 다른 이유:
     * - exists 쿼리 제거로 성능 최적화
     * - DB 제약조건을 활용한 안전한 멱등성 보장
     * - Race condition 원천 방지
     */
    @Transactional
    public boolean processWithUniqueConstraintAndRetry(Long balanceId,
                                                       BigDecimal amount,
                                                       String txId,
                                                       String transactionToken) {
        int attempt = 0;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                // 트랜잭션 선등록 시도 (멱등성 체크)
                Transaction transaction = Transaction.create(amount);
                transaction.updateTxId(txId);
                transactionRepository.save(transaction);

                // 잔액 조회 및 처리
                Balance balance = balanceRepository.findById(balanceId)
                        .orElseThrow(() -> new BalanceProcessingException("잔액을 찾을 수 없음: " + balanceId));

                balance.changeBalance(amount);
                balance.updateTransactionToken(transactionToken);  // 유니크 제약조건용 토큰 설정
                balanceRepository.save(balance);

                return true;

            } catch (DataIntegrityViolationException e) {
                // txId나 transactionToken이 이미 존재하는 경우
                return false; // 이미 처리된 요청으로 간주

            } catch (Exception e) {
                if (attempt >= MAX_RETRY_ATTEMPTS - 1) {
                    handleException("유니크 제약 조건 처리", e, balanceId);
                    return false;
                }

                // 고정 지연 시간으로 재시도
                log.warn("유니크 제약 조건 처리 실패 - 재시도 {}/{}. balanceId: {}. {}ms 후 재시도...",
                         attempt + 1, MAX_RETRY_ATTEMPTS, balanceId, INITIAL_RETRY_DELAY_MS);

                sleep(INITIAL_RETRY_DELAY_MS);
                attempt++;
            }
        }
        return false;
    }

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