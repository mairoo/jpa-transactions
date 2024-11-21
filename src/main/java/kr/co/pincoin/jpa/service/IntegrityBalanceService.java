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
public class IntegrityBalanceService {
    private final BalanceRepository balanceRepository;

    private final TransactionRepository transactionRepository;

    /**
     * 비관적 락을 사용한 잔액 변경
     * - 데이터베이스 수준에서 락을 걸어 동시 접근을 차단
     * - 실제 데이터 충돌이 많은 경우 유용
     */
    // @Transactional: 메서드 전체를 하나의 트랜잭션으로 처리
    @Transactional
    public void updateBalanceWithPessimisticLock(Long balanceId, BigDecimal amount) {
        // 비관적 락을 사용하여 Balance 조회. 없으면 예외 발생
        Balance balance = balanceRepository.findByIdWithPessimisticLock(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 (입금 또는 출금)
        balance.changeBalance(amount);
        // 트랜잭션 이력 저장
        transactionRepository.save(Transaction.create(amount));

        // 변경된 잔액 정보 저장
        balanceRepository.save(balance);
    }

    /**
     * 낙관적 락을 사용한 잔액 변경
     * - 버전 정보를 사용하여 충돌을 감지
     * - 실제 충돌이 적은 경우 효율적
     */
    // @Transactional: 메서드 전체를 하나의 트랜잭션으로 처리
    @Transactional
    public void updateBalanceWithOptimisticLock(Long balanceId, BigDecimal amount) {
        // 낙관적 락을 사용하여 Balance 조회. 없으면 예외 발생
        Balance balance = balanceRepository.findByIdWithOptimisticLock(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 (입금 또는 출금)
        balance.changeBalance(amount);
        // 트랜잭션 이력 저장
        transactionRepository.save(Transaction.create(amount));

        // 변경된 잔액 정보 저장
        balanceRepository.save(balance);
    }

    /**
     * 유니크 제약 조건을 사용한 잔액 변경
     * - 트랜잭션 토큰을 통해 중복 처리를 방지
     * - 멱등성이 필요한 경우 유용
     */
    // @Transactional: 메서드 전체를 하나의 트랜잭션으로 처리
    @Transactional
    public boolean updateBalanceWithUniqueConstraint(Long balanceId, BigDecimal amount, String transactionToken) {
        // 이미 처리된 트랜잭션 토큰인지 확인
        if (balanceRepository.findByTransactionToken(transactionToken).isPresent()) {
            // 이미 처리된 경우 false 반환
            return false;
        }

        // Balance 조회. 없으면 예외 발생
        Balance balance = balanceRepository.findById(balanceId)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 잔액 정보를 찾을 수 없습니다: " + balanceId));

        // 잔액 변경 (입금 또는 출금)
        balance.changeBalance(amount);
        // 트랜잭션 이력 저장
        transactionRepository.save(Transaction.create(amount));

        // 처리된 트랜잭션 토큰 설정
        balance.updateTransactionToken(transactionToken);
        // 변경된 잔액 정보 저장
        balanceRepository.save(balance);
        // 정상 처리됨을 의미하는 true 반환
        return true;
    }

    /**
     * 초기 잔액으로 Balance 생성
     */
    // @Transactional: 메서드 전체를 하나의 트랜잭션으로 처리
    @Transactional
    public Balance createBalance(BigDecimal initialBalance) {
        // 초기 잔액으로 Balance 객체를 생성하고 저장
        return balanceRepository.save(Balance.createWithInitialBalance(initialBalance));
    }
}