package kr.co.pincoin.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Getter
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private BigDecimal amount;

    /**
     * 트랜잭션을 생성합니다.
     * 양수: 입금, 음수: 출금
     *
     * @param amount 금액 (양수: 입금, 음수: 출금)
     * @return Transaction 객체
     */
    public static Transaction create(BigDecimal amount) {
        validateAmount(amount);
        return Transaction.builder()
                .amount(amount)
                .build();
    }

    /**
     * 금액이 0이 아닌지 검증합니다.
     *
     * @param amount 검증할 금액
     * @throws IllegalArgumentException 유효하지 않은 금액인 경우
     */
    private static void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("금액은 null일 수 없습니다.");
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("금액은 0일 수 없습니다.");
        }
    }
}