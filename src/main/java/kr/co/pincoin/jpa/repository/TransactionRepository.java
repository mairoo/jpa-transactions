package kr.co.pincoin.jpa.repository;

import kr.co.pincoin.jpa.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
}
