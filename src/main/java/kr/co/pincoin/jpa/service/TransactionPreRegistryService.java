package kr.co.pincoin.jpa.service;

import jakarta.persistence.EntityNotFoundException;
import kr.co.pincoin.jpa.entity.Balance;
import kr.co.pincoin.jpa.entity.Transaction;
import kr.co.pincoin.jpa.repository.BalanceRepository;
import kr.co.pincoin.jpa.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TransactionPreRegistryService {
    private final BalanceRepository balanceRepository;

    private final TransactionRepository transactionRepository;

    /**
     * 비관적 락 버전
     * <p>
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
    public boolean
    processWithPessimisticLock(Long balanceId,
                               BigDecimal amount,
                               String txId) {
        try {
            // 트랜잭션 먼저 생성 시도 (유니크 제약조건으로 멱등성 체크)
            Transaction transaction = Transaction.create(amount);
            transaction.updateTxId(txId);
            transactionRepository.save(transaction);

            // 비관적 락으로 잔액 정보 조회 및 락 획득
            Balance balance = balanceRepository.findByIdWithPessimisticLock(balanceId)
                    .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

            // 잔액 변경 후 저장
            balance.changeBalance(amount);
            balanceRepository.save(balance);

            return true;

        } catch (DataIntegrityViolationException e) {
            return false; // 이미 처리된 요청 (txId 중복)
        }
    }

    /**
     * 낙관적 락 버전
     * <p>
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
    public boolean
    processWithOptimisticLock(Long balanceId,
                              BigDecimal amount,
                              String txId) {
        try {
            // 트랜잭션 먼저 생성 시도 (유니크 제약조건으로 멱등성 체크)
            Transaction transaction = Transaction.create(amount);
            transaction.updateTxId(txId);
            transactionRepository.save(transaction);

            // 낙관적 락으로 잔액 정보 조회
            Balance balance = balanceRepository.findByIdWithOptimisticLock(balanceId)
                    .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

            // 잔액 변경 후 저장
            balance.changeBalance(amount);
            balanceRepository.save(balance);

            return true;

        } catch (DataIntegrityViolationException e) {
            return false; // 이미 처리된 요청 (txId 중복)
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RuntimeException("낙관적 락 충돌 발생, 재시도 필요", e);
        }
    }

    /**
     * 유니크 제약 조건 버전
     * <p>
     * 트랜잭션 처리 프로세스:
     * 1. 트랜잭션 선등록으로 멱등성 보장 (unique constraint 활용)
     * 2. 잔액 처리 (유니크 제약조건 활용)
     * <p>
     * 일반적인 흐름과 다른 이유:
     * - exists 쿼리 제거로 성능 최적화
     * - DB 제약조건을 활용한 안전한 멱등성 보장
     * - Race condition 원천 방지
     */
    @Transactional
    public boolean
    processWithUniqueConstraint(Long balanceId,
                                BigDecimal amount,
                                String txId,
                                String transactionToken) {
        try {
            // 트랜잭션 먼저 생성 시도
            Transaction transaction = Transaction.create(amount);
            transaction.updateTxId(txId);
            transactionRepository.save(transaction);

            // 잔액 정보 조회
            Balance balance = balanceRepository.findById(balanceId)
                    .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

            // 잔액 변경 및 토큰 설정 후 저장
            balance.changeBalance(amount);
            balance.updateTransactionToken(transactionToken);  // transactionToken도 유니크 제약조건
            balanceRepository.save(balance);

            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // 이미 처리된 요청 (txId 또는 transactionToken 중복)
        }
    }
}
