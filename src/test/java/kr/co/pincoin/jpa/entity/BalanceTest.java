package kr.co.pincoin.jpa.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceTest {
    @Test
    void balanceOperationTest() {
        // 초기 잔액 1000원으로 Balance 생성
        Balance balance = Balance.createWithInitialBalance(new BigDecimal("1000.00"));
        assertThat(balance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1000.00"));

        // 입금 테스트 (양수)
        balance.changeBalance(new BigDecimal("500.00"));
        assertThat(balance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1500.00"));

        // 출금 테스트 (음수)
        balance.changeBalance(new BigDecimal("-300.00"));
        assertThat(balance.getBalance())
                .isEqualByComparingTo(new BigDecimal("1200.00"));

        // 잔액 부족 테스트
        assertThatThrownBy(() -> balance.changeBalance(new BigDecimal("-1500.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잔액이 부족합니다");

        // null 금액 테스트
        assertThatThrownBy(() -> balance.changeBalance(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null일 수 없습니다");

        // 트랜잭션 토큰 업데이트 테스트
        String token = "test-token";
        balance.updateTransactionToken(token);
        assertThat(balance.getTransactionToken())
                .isEqualTo(token);
    }
}