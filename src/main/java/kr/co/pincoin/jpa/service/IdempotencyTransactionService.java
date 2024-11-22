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
public class IdempotencyTransactionService {
    private final BalanceRepository balanceRepository;

    private final TransactionRepository transactionRepository;

    /**
     * 비관적 락과 멱등성을 조합한 구현
     * 1. txId로 중복 요청 체크
     * 2. 비관적 락으로 잔액 테이블 락 획득
     * 3. 잔액 업데이트 후 거래 기록
     */
    @Transactional
    public boolean processWithPessimisticLock(Long balanceId, BigDecimal amount, String txId) {
        // 멱등성 체크 - 이미 처리된 거래인지 확인
        if (transactionRepository.existsByTxId(txId)) {
            return false; // 이미 처리된 요청
        }

        // 비관적 락으로 잔액 정보 조회 및 락 획득
        Balance balance = balanceRepository.findByIdWithPessimisticLock(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 후 저장 (잔액 무결성)
        balance.changeBalance(amount);
        balanceRepository.save(balance);

        // 거래 기록 생성 및 저장 (거래 이력)
        Transaction transaction = Transaction.create(amount);
        transaction.updateTxId(txId);
        transactionRepository.save(transaction);

        return true; // 정상 처리 완료
    }

    /**
     * 낙관적 락과 멱등성을 조합한 구현
     * 1. txId로 중복 요청 체크
     * 2. 낙관적 락(@Version)으로 변경 감지
     * 3. 잔액 업데이트 후 거래 기록
     */
    @Transactional
    public boolean processWithOptimisticLock(Long balanceId, BigDecimal amount, String txId) {
        // 멱등성 체크 - 이미 처리된 거래인지 확인
        if (transactionRepository.existsByTxId(txId)) {
            return false; // 이미 처리된 요청
        }

        // 낙관적 락으로 잔액 정보 조회
        Balance balance = balanceRepository.findByIdWithOptimisticLock(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 후 저장 (잔액 무결성)
        balance.changeBalance(amount);
        balanceRepository.save(balance);

        // 거래 기록 생성 및 저장 (거래 이력)
        Transaction transaction = Transaction.create(amount);
        transaction.updateTxId(txId);
        transactionRepository.save(transaction);

        return true; // 정상 처리 완료
    }

    /**
     * 유니크 제약 조건과 멱등성을 조합한 구현
     * 1. txId와 token으로 중복 요청 체크
     * 2. 거래 기록 먼저 저장 (실패하면 롤백)
     * 3. 잔액 업데이트 및 토큰 저장
     */
    @Transactional
    public boolean processWithUniqueConstraint(Long balanceId, BigDecimal amount, String txId,
                                               String transactionToken) {
        // 멱등성 체크 - 이미 처리된 거래인지 확인
        if (transactionRepository.existsByTxId(txId)) {
            return false; // 이미 처리된 요청
        }

        // 이미 처리된 토큰인지 확인 - 트랜잭션 무결성 요건
        if (balanceRepository.findByTransactionToken(transactionToken).isPresent()) {
            return false; // 이미 처리된 토큰
        }

        // 잔액 정보 조회
        Balance balance = balanceRepository.findById(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 거래 기록 먼저 생성 및 저장 (실패 시 롤백)
        Transaction transaction = Transaction.create(amount);
        transaction.updateTxId(txId);
        transactionRepository.save(transaction);

        // 잔액 변경 및 토큰 설정 후 저장
        balance.changeBalance(amount);
        balance.updateTransactionToken(transactionToken);
        balanceRepository.save(balance);

        return true; // 정상 처리 완료
    }
}