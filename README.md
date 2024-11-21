# 목표
Spring Data JPA 이용한 동시성 문제 해결 이해

- 예제 테이블
  - 잔액: Balance(id, balance) - 트랜잭션 무결성 보장 필요
  - 거래: Transaction(id, amount) - 중복 거래가 추가 되지 않도록 멱등성 보장 필요
  - 동시성 문제와 fk 문제는 다르므로 여기서 fk 설정은 없다.
- 동시성 문제 발생 가능한
  - 잔액 불일치 문제 해결 전략: 트랜잭션 무결성과 락
  - 중복 거래 방지 전략: 멱등성 보장

# 잔액 불일치 문제 해결 전략
| 락 종류 | 구현 레벨 | 구현 방식 |
|---------|-----------|-----------|
| 비관적 락 | 데이터베이스 레벨 | `SELECT FOR UPDATE` 구문 |
| 낙관적 락 | 애플리케이션 레벨 | JPA `@Version` 애노테이션 |
| 유니크 제약 조건 | 데이터베이스 레벨 | `uniq` 인덱스 |

주요 개념
- 실제 DB에 락을 거는 것은 비관적 락과 네임드 락이다. 나머지는 락 이름이 있지만 진짜 DB 락이 아니다.
- 락이란 내가 잔액 테이블을 읽고 나서 쓰기 작업을 마칠 때까지 다른 데서 접근할 수 없도록 한다.

## 낙관적 락과 유니크 제약 조건 개념 차이
| 기법 | 충돌 감지 시점 | 관리 방식 | 처리 방식 | 예외 유형 |
|--------|--------------|-----------|-----------|-----------|
| 낙관적 락 | 트랜잭션 커밋 시점 | JPA 관리 `@Version` 필드 | • JPA가 버전 관리 자동 수행<br>• 수정 시 버전 체크와 증가 자동 처리 | `OptimisticLockException` |
| 유니크 제약 조건 | 트랜잭션 커밋 시점 | 개발자 관리 `Uniq` 필드 | • 개발자가 직접 유니크한 값을 생성하고 관리<br>• DB의 유니크 제약조건 활용 | `DataIntegrityViolationException` |
  - 
## 락과 유니크 제약 조건의 개념 차이
| 처리 방식 | 핵심 목표 | 처리 순서 | 특징 |
|---------|-----------|-----------|---------|
| 비관적 락 / 낙관적 락 | 계좌 잔액의 동시성 | 1. 락 획득<br>2. 잔액 갱신<br>3. 거래 기록 | • 락 획득 즉시 잔액 갱신<br>• 락 보호 하에 거래 기록 |
| 유니크 제약 조건 | 거래의 유일성 | 1. 거래 기록<br>2. 성공 확인<br>3. 잔액 갱신 | • 거래 기록 성공 확인<br>• 확인 후 잔액 갱신 |

# 중복 거래 방지 전략
## 멱등성 보장
- 멱등성(Idempotency)은 동일한 요청을 여러 번 수행하더라도 결과가 달라지지 않는 성질

멱등성의 중요성
- 네트워크 불안정성 대응
  - 클라이언트 타임아웃
  - 응답 유실
  - 네트워크 단절

멱등성 보장 키의 특징
- 글로벌 유니크해야 함
- 예측 불가능해야 함
- 시간 순서 추적 가능하면 좋음
- 적절한 길이 제한 필요

**멱등성 보장 키와 동시성 제어 필드는 용도가 다르다.**

`@Version`으로 멱등성 구현 시도 시 문제점
- 재시도 시 `OptimisticLockException` 예외 발생
- 클라이언트 상태 추적 불가
- 실패한 요청과 새 요청 구분 불가

비즈니스 키로 멱등성 구현 시도 시 문제점
- 같은 주문에 대한 여러 시도 구분 불가
- 취소/재시도 시나리오 처리 어려움

## 멱등성 보장 키 생성 전략

| 전략             | 장점                                                     | 단점                                          |
| -------------- | ------------------------------------------------------ | ------------------------------------------- |
| 1. UUID 기반     | • 충돌 가능성이 극히 낮음<br>• 구현이 매우 단순<br>• 분산 시스템에서도 안전       | • 36자로 길이가 김<br>• 가독성이 떨어짐<br>• 시간 순서 파악 불가 |
| 2. 타임스탬프 + 랜덤값 | • 시간 정보 포함으로 디버깅 용이<br>• UUID보다 짧은 길이<br>• 시간 순서 파악 가능 | • 동일 시간에 생성 시 충돌 가능성 존재<br>• 서버 간 시간 동기화 필요 |
| 3. 비즈니스 규칙 기반  | • 비즈니스 의미 포함<br>• 가독성이 좋음<br>• 추적 및 분석 용이              | • 구현이 복잡<br>• 시퀀스 관리 필요<br>• 확장성 제한될 수 있음   |
| 4. 하이브리드       | • 비즈니스 식별 가능<br>• 시간 순서 파악 가능<br>• 충돌 가능성 매우 낮음        | • 상대적으로 긴 길이<br>• 구현 복잡도 증가<br>• 형식 관리 필요   |
### UUID
```java
@Entity
public class Payment {
    @Column(unique = true)
    private String txId = UUID.randomUUID().toString();
}
```
### 타임스탬프 + 랜덤값
```java
public class TxIdGenerator {
    public static String generate() {
        long timestamp = System.currentTimeMillis();
        int random = new Random().nextInt(10000);
        return String.format("%d-%04d", timestamp, random);
    }
}
```
### 비즈니스 규칙 기반
```java
public class OrderTxIdGenerator {
    public String generate(String storeId, String date) {
        return String.format("ORD-%s-%s-%06d",
            storeId,
            date,
            getSequence(storeId, date)
        );
    }
    
    // 예: ORD-STORE001-20240321-000001
    private synchronized long getSequence(String storeId, String date) {
        // Redis나 DB에서 시퀀스 관리
        return redisTemplate.opsForValue()
            .increment(String.format("seq:%s:%s", storeId, date));
    }
}
```
### 하이브리드 전략
```java
public class HybridTxIdGenerator {
    public static String generate(String prefix) {
        return String.format("%s-%d-%s",
            prefix,
            System.currentTimeMillis(),
            RandomStringUtils.randomAlphanumeric(8)
        );
    }
}

// 사용 예: PAY-1679881234567-Xk4Mn9Pq
```