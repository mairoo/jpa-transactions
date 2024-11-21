package kr.co.pincoin.jpa.service;

import jakarta.persistence.EntityNotFoundException;
import kr.co.pincoin.jpa.entity.Balance;
import kr.co.pincoin.jpa.entity.Transaction;
import kr.co.pincoin.jpa.repository.BalanceRepository;
import kr.co.pincoin.jpa.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class IdempotentTransactionService {
    private final BalanceRepository balanceRepository;

    private final TransactionRepository transactionRepository;

    /**
     * 비관적 락과 txId를 함께 사용한 멱등성이 보장된 잔액 변경
     * - 데이터베이스 수준의 락으로 동시성 제어
     * - txId로 중복 요청 방지
     */
    @Transactional
    public boolean processWithPessimisticLock(Long balanceId, BigDecimal amount, String txId) {
        // 이미 처리된 txId인지 확인 - 멱등성 보장 요건
        if (transactionRepository.existsByTxId(txId)) {
            return false; // 이미 처리된 요청
        }

        // 비관적 락으로 Balance 조회
        Balance balance = balanceRepository.findByIdWithPessimisticLock(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 및 거래 이력 생성
        balance.changeBalance(amount);
        Transaction transaction = Transaction.create(amount);
        transaction.updateTxId(txId); // 거래 식별자 설정

        // 변경사항 저장
        balanceRepository.save(balance);
        transactionRepository.save(transaction);

        return true; // 정상 처리 완료
    }

    /**
     * 낙관적 락과 txId를 함께 사용한 멱등성이 보장된 잔액 변경
     * - 버전 정보로 동시성 제어
     * - txId로 중복 요청 방지
     */
    @Transactional
    public boolean processWithOptimisticLock(Long balanceId, BigDecimal amount, String txId) {
        // 이미 처리된 txId인지 확인 - 멱등성 보장 요건
        if (transactionRepository.existsByTxId(txId)) {
            return false; // 이미 처리된 요청
        }

        // 낙관적 락으로 Balance 조회
        Balance balance = balanceRepository.findByIdWithOptimisticLock(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 및 거래 이력 생성
        balance.changeBalance(amount);
        Transaction transaction = Transaction.create(amount);
        transaction.updateTxId(txId); // 거래 식별자 설정

        // 변경사항 저장
        balanceRepository.save(balance);
        transactionRepository.save(transaction);

        return true; // 정상 처리 완료
    }

    /**
     * 유니크 제약 조건과 txId를 함께 사용한 멱등성이 보장된 잔액 변경
     * - 트랜잭션 토큰으로 동시성 제어
     * - txId로 중복 요청 방지
     */
    @Transactional
    public boolean processWithUniqueConstraint(Long balanceId, BigDecimal amount, String txId,
                                               String transactionToken) {
        // 이미 처리된 txId인지 확인 - 멱등성 보장 요건
        if (transactionRepository.existsByTxId(txId)) {
            return false; // 이미 처리된 요청
        }

        // 이미 처리된 토큰인지 확인 - 트랜잭션 무결성 요건
        if (balanceRepository.findByTransactionToken(transactionToken).isPresent()) {
            return false; // 이미 처리된 토큰
        }

        // Balance 조회
        Balance balance = balanceRepository.findById(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 및 거래 이력 생성
        balance.changeBalance(amount);
        Transaction transaction = Transaction.create(amount);
        transaction.updateTxId(txId); // 거래 식별자 설정

        // 트랜잭션 토큰 설정
        balance.updateTransactionToken(transactionToken);

        // 변경사항 저장
        balanceRepository.save(balance);
        transactionRepository.save(transaction);

        return true; // 정상 처리 완료
    }
}