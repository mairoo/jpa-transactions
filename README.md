# 목표

Spring Data JPA 이용한 트랜잭션 무결성과 멱등성

## 정의

- 동시성 (Concurrency)
  - 여러 사람이 동시에 같은 데이터를 수정하려고 할 때 발생하는 문제
  - 예: 두 명이 동시에 같은 은행 계좌에서 출금하려고 할 때, 잔액이 꼬일 수 있음

- 무결성 (Integrity)
  - 데이터가 정확하고 일관된 상태를 유지하는 것
  - 동시성 문제가 발생하면 무결성이 깨질 수 있음
  - 예: 계좌 이체 시 출금 계좌에서 돈이 빠져나가면, 입금 계좌에 반드시 그만큼 들어와야 함

- 멱등성 (Idempotency): 달성 목표
  - 같은 동작을 여러 번 해도 결과가 달라지지 않는 성질
  - 네트워크 오류가 발생하면 멱등성이 깨질 수 있음
  - 예: 고객이 결제 버튼을 실수로 여러 번 눌러도 한 번만 결제되어야 함

즉, 동시성은 우리가 처한 **문제 상황**이고 무결성과 멱등성은 우리가 **달성해야할 목표**이다.

원칙: "필요할 때까지 도입하지 않는다"는 원칙을 기본으로 하되, 비즈니스의 핵심적인 데이터 무결성 요구사항은 처음부터 고려하는 것이 바람직하다.

- 동시 요청이 빈번하고 데이터 충돌 가능성이 높은 경우
- 금전적 처리나 재고 관리처럼 정확성이 매우 중요한 경우
- 트랜잭션이 짧고 충돌 시 비용이 매우 큰 경우

# 예시

- 예제 테이블
  - 잔액: Balance(id, balance) - 트랜잭션 무결성 보장 필요
  - 거래: Transaction(id, amount) - 중복 거래가 추가 되지 않도록 멱등성 보장 필요
  - 동시성 문제와 fk 문제는 다르므로 여기서 fk 설정은 없다.

- 문제와 해결책
  - 동시성 문제로 발생 가능한 **잔액 불일치** 해결 전략: 트랜잭션 무결성과 락
  - 네트워크 오류로 발생 가능한 **중복 거래** 방지 전략: 멱등성 보장

# 잔액 불일치 해결 전략
| 락 종류      | 구현 레벨     | 구현 방식                  |
|-----------|-----------|------------------------|
| 비관적 락     | 데이터베이스 레벨 | `SELECT FOR UPDATE` 구문 |
| 낙관적 락     | 애플리케이션 레벨 | JPA `@Version` 애노테이션   |
| 유니크 제약 조건 | 데이터베이스 레벨 | `uniq` 인덱스             |

주요 개념
- 실제 DB에 락을 거는 것은 비관적 락과 네임드 락이다. 나머지는 락 이름이 있지만 진짜 DB 락이 아니다.
- 락이란 내가 잔액 테이블을 읽고 나서 쓰기 작업을 마칠 때까지 다른 데서 접근할 수 없도록 한다.

## 비관적 락과 낙관적 락 비교

| 구분     | 비관적 락                                                                                       | 낙관적 락                                                                                                                                                                  |
|--------|---------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 동작 방식  | • 먼저 락을 잡은 트랜잭션이 Winner<br>• 다른 트랜잭션은 즉시 실패<br>• 실패 시 롤백으로 모든 변경사항 원복<br>• 클라이언트에게 즉시 실패 통지 | • 동시에 읽고 작업 가능<br>• 커밋 시점에 충돌 감지<br>• 늦게 커밋하는 트랜잭션이 실패                                                                                                                 |
| 적합한 상황 | • 단일 레코드의 빈번한 갱신<br>• 높은 동시성 상황<br>• 실시간 충돌 감지가 필요한 경우                                      | • 여러 레코드의 동시 갱신<br>• 실제 충돌이 적은 경우<br>• DB 연결이 제한적인 경우                                                                                                                  |
| 장점     | • 초기에 실패 확인 가능<br>• 불필요한 작업 방지<br>• 성공한 트랜잭션은 재시도 없음                                        | • DB 연결 자원 효율적<br>• 동시 작업 가능<br>• 락 대기 시간 없음                                                                                                                           |
| 실제 예시 | 은행 ATM 예시<br>• 하나의 계좌에서 여러 ATM이 동시에 출금 시도<br>• 정확한 잔액 처리가 매우 중요<br>• 한 번에 하나의 거래만 처리되어야 함<br>• 다른 ATM은 대기했다가 순서대로 처리<br>→ 단일 계좌(단일 레코드)에 대한 빈번한 동시 접근을 안전하게 처리 | 온라인 쇼핑몰 예시<br>• 여러 상품의 재고수량을 한 번에 업데이트<br>• 대부분의 경우 다른 사람이 동시에 같은 상품을 구매하지 않음<br>• 동시 구매가 발생하더라도 재시도로 해결 가능<br>• DB 연결을 오래 유지할 필요 없음<br>→ 여러 상품(여러 레코드)의 재고를 효율적으로 관리 |

## 낙관적 락과 유니크 제약 조건 개념 차이
| 기법 | 충돌 감지 시점 | 관리 방식 | 처리 방식 | 예외 유형 |
|--------|--------------|-----------|-----------|-----------|
| 낙관적 락 | 트랜잭션 커밋 시점 | JPA 관리 `@Version` 필드 | • JPA가 버전 관리 자동 수행<br>• 수정 시 버전 체크와 증가 자동 처리 | `OptimisticLockException` |
| 유니크 제약 조건 | 트랜잭션 커밋 시점 | 개발자 관리 `Uniq` 필드 | • 개발자가 직접 유니크한 값을 생성하고 관리<br>• DB의 유니크 제약조건 활용 | `DataIntegrityViolationException` |

## 락과 유니크 제약 조건의 개념 차이
| 처리 방식 | 핵심 목표 | 처리 순서 | 특징 |
|---------|-----------|-----------|---------|
| 비관적 락 / 낙관적 락 | 계좌 잔액의 동시성 | 1. 락 획득<br>2. 잔액 갱신<br>3. 거래 기록 | • 락 획득 즉시 잔액 갱신<br>• 락 보호 하에 거래 기록 |
| 유니크 제약 조건 | 거래의 유일성 | 1. 거래 기록<br>2. 성공 확인<br>3. 잔액 갱신 | • 거래 기록 성공 확인<br>• 확인 후 잔액 갱신 |

## 구현 방식 상세

### 비관적 락

- `@Query`로 직접 JPQL 작성
- `@Lock(LockModeType.PESSIMISTIC_WRITE)` 비관적 락 획득 명시
- DB 레벨 접근 제어를 이용하므로 **SELECT(잔액), UPDATE(잔액), INSERT(거래)** 기본 로직이 바뀌진 않음

### 낙관적 락

- `@Version` 애노테이션 필드를 추가한다.
- 조회 때 version 필드 정수값을 가져오고 업데이트할 때 현재 레코드 version 값과 불일치로 업데이트 결과 레코드 수가 0이 되면 `OptimisticLockException` 예외를 발생시켜서 트랜잭션이 롤백 시킴
- JPA 기능 애플리케이션 레벨 방법이지만 락 기법과 유사하여 **SELECT(잔액), UPDATE(잔액), INSERT(거래)** 역시 기본 로직 동일

### 유니크 제약 조건

- JPA/DB가 자동으로 관리하는 기술적인 식별자 Auto increment PK와 별개로 비즈니스적으로 의미있는 필드 유니크 제약 조건을 걸어줌
- 유니크 제약 비즈니스 키가 이미 존재한다는 것은 이미 트랜잭션 처리가 완료된 것으로 간주
- 새 트랜잭션의 비즈니스 키가 다르다면 데이터 처리 완료 후 이 비즈니스 키로 업데이트
- **SELECT(비즈니스 토큰), SELECT(잔액), INSERT(거래), UPDATE(비즈니스 토큰, 잔액)** 토큰 로직 필요

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

유니크 제약 비즈니스 키로 멱등성 구현 시도 시 문제점
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

# 그 외

| 락 종류   | 장점                                            | 단점                                      | 실무 사례                                   | 구현 핵심 아이디어                                                                                  |
| ------ | --------------------------------------------- | --------------------------------------- |-----------------------------------------| ------------------------------------------------------------------------------------------- |
| 비관적 락  | • 데이터 정합성 보장<br>• 충돌이 많은 경우 효율적<br>• 즉시 실패 확인 | • DB 성능 저하<br>• 확장성 제한<br>• 데드락 위험      | • 계좌 이체<br>• 재고 관리           | • @Lock(LockModeType<br>.PESSIMISTIC_WRITE)<br>• select for update 구문 사용<br>• 트랜잭션 격리 수준 활용 |
| 낙관적 락  | • 리소스 효율적<br>• 확장성 좋음<br>• 충돌 적을 때 유리         | • 충돌 시 재시도 필요<br>• 늦은 실패 감지<br>• 구현 복잡도 | • 게시글 수정<br>• 상품 리뷰       | • @Version 컬럼 사용<br>• 버전 불일치시 예외 처리<br>• 재시도 로직 구현                                          |
| 유니크 제약 | • 구현 단순<br>• DB 레벨 보장<br>• 안정성 높음             | • 유연성 부족<br>• 제약 조건 관리<br>• 성능 영향       | • 주문 번호<br>• 결제 ID<br>• 예약 번호<br>• 좌석 예약 | • unique 인덱스 활용<br>• 멱등성 키 생성<br>• try-catch로 중복 처리                                         |
| 네임드 락  | • 테이블 단위 제어<br>• 세밀한 제어 가능<br>• DB 기능 활용      | • DB 의존성<br>• 데드락 위험<br>• 복잡한 관리        | • 배치 작업<br>• 정산 처리<br>• 대용량 업데이트        | • GET_LOCK() 함수 사용<br>• 타임아웃 설정<br>• 명시적 락 해제                                               |
| 분산 락   | • 다중 서버 지원<br>• 높은 확장성<br>• 유연한 정책 설정         | • 인프라 비용<br>• 네트워크 오버헤드<br>• 구현 복잡도     | • 스케줄러<br>• 캐시 갱신<br>• 결제 처리            | • Redis/Zookeeper 활용<br>• TTL 설정<br>• 락 획득 재시도 로직                                           |

# 소스 코드 바로가기

## 엔티티

- [잔액 엔티티](/src/main/java/kr/co/pincoin/jpa/entity/Balance.java)
- [거래 엔티티](/src/main/java/kr/co/pincoin/jpa/entity/Transaction.java)

## JPA 리파지토리

- [잔액 리파지토리](/src/main/java/kr/co/pincoin/jpa/repository/BalanceRepository.java)
- [거래 리파지토리](/src/main/java/kr/co/pincoin/jpa/repository/TransactionRepository.java)

## 서비스

- [잔액 무결성 보장 예제](/src/main/java/kr/co/pincoin/jpa/service/IntegrityBalanceService.java)

  비관적 락 JPQL
    ```sql
    -- 잔액 조회 with 락 획득
    SELECT b.* FROM balance b WHERE b.id = ? FOR UPDATE;
    -- 잔액 업데이트
    UPDATE balance SET balance = ?, version = ? WHERE id = ?;
    -- 거래 내역 저장
    INSERT INTO transactions (amount) VALUES (?);
    ```
  낙관적 락 JPQL
    ```sql
    -- 잔액 조회
    SELECT b.* FROM balance b WHERE b.id = ?;   
    -- 잔액 업데이트 (버전 체크)
    UPDATE balance SET balance = ?, version = ? WHERE id = ? AND version = ?;
    -- 거래 내역 저장
    INSERT INTO transactions (amount) VALUES (?);   
    ```
  유니크 제약 조건 JPQL
    ```sql
    -- 토큰으로 중복 체크
    SELECT b.* FROM balance b WHERE b.transaction_token = ?;
    -- 잔액 조회
    SELECT b.* FROM balance b WHERE b.id = ?;
    -- 거래 기록 저장
    INSERT INTO transactions (amount) VALUES (?);
    -- 잔액과 토큰 업데이트
    UPDATE balance SET balance = ?, transaction_token = ? WHERE id = ?;
    ```
- [잔액 무결성 + 거래 멱등성 보장 예제](/src/main/java/kr/co/pincoin/jpa/service/IdempotentTransactionService.java)

  상기 쿼리 세 케이스 모두 실행 전에 멱등성 보장 확인
    ```sql
    -- txId 중복 체크
    SELECT EXISTS(SELECT 1 FROM transactions WHERE tx_id = ?);  
    ```
- [잔액 무결성 + 거래 멱등성 보장 + 재시도](/src/main/java/kr/co/pincoin/jpa/service/ResilientTransactionService.java)

  상기 쿼리와 동일하고 재시도 로직만 추가

## 단위 테스트

- [잔액 단위 테스트](/src/test/java/kr/co/pincoin/jpa/entity/BalanceTest.java)
- [거래 단위 테스트](/src/test/java/kr/co/pincoin/jpa/entity/TransactionTest.java)

## 서비스 테스트

- [잔액 무결성 보장 테스트](src/test/java/kr/co/pincoin/jpa/service/IntegrityBalanceServiceTest.java)
- [잔액 무결성 보장 + 거래 멱등성 보장 테스트](/src/test/java/kr/co/pincoin/jpa/service/IdempotentTransactionServiceTest.java)
- [잔액 무결성 보장 + 거래 멱등성 보장 + 재시도 테스트](/src/test/java/kr/co/pincoin/jpa/service/ResilientTransactionServiceTest.java)
