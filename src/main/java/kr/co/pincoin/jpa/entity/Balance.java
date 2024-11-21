package kr.co.pincoin.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "balances")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Getter
public class Balance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private BigDecimal balance;

    @Version
    private Long version;

    @Column(unique = true)
    private String transactionToken;

    /**
     * 초기 잔액으로 Balance 객체를 생성합니다.
     *
     * @param initialBalance 초기 잔액
     * @return Balance 객체
     */
    public static Balance createWithInitialBalance(BigDecimal initialBalance) {
        return Balance.builder()
                .balance(initialBalance)
                .build();
    }

    /**
     * 잔액을 변경합니다.
     * 양수: 입금으로 증가, 음수: 출금으로 감소
     *
     * @param amount 변경할 금액 (양수: 입금, 음수: 출금)
     * @throws IllegalArgumentException null인 경우
     * @throws IllegalStateException    출금 시 잔액이 부족한 경우
     */
    public void changeBalance(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("금액은 null일 수 없습니다.");
        }

        BigDecimal newBalance = this.balance.add(amount);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("잔액이 부족합니다. 현재 잔액: " + this.balance);
        }

        this.balance = newBalance;
    }

    /**
     * 트랜잭션 토큰을 설정합니다.
     *
     * @param transactionToken 설정할 트랜잭션 토큰
     */
    public void updateTransactionToken(String transactionToken) {
        this.transactionToken = transactionToken;
    }
}