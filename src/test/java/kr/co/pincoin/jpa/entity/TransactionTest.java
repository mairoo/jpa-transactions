package kr.co.pincoin.jpa.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTest {
    @Test
    void transactionCreationTest() {
        // 입금 트랜잭션 생성 테스트 (양수)
        BigDecimal depositAmount = new BigDecimal("100.00");
        Transaction depositTx = Transaction.create(depositAmount);
        assertThat(depositTx.getAmount())
                .isEqualByComparingTo(depositAmount);

        // 출금 트랜잭션 생성 테스트 (음수)
        BigDecimal withdrawalAmount = new BigDecimal("-50.00");
        Transaction withdrawalTx = Transaction.create(withdrawalAmount);
        assertThat(withdrawalTx.getAmount())
                .isEqualByComparingTo(withdrawalAmount);

        // 0원 트랜잭션 생성 시도
        assertThatThrownBy(() -> Transaction.create(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0일 수 없습니다");

        // null 금액 트랜잭션 생성 시도
        assertThatThrownBy(() -> Transaction.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null일 수 없습니다");
    }
}