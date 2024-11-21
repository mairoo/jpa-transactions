package kr.co.pincoin.jpa.repository;

import kr.co.pincoin.jpa.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    // 멱등성 보장을 위해 txId로 거래 존재 여부 확인
    boolean existsByTxId(String txid);
}
