# 프로젝트 개요

백엔드 C 과제인 `알림 발송 시스템` 구현입니다.  
수강 신청 완료, 결제 확정, 강의 시작 D-1, 취소 처리 이벤트에 대해 `EMAIL` 또는 `IN_APP` 알림 요청을 접수하고, DB에 영속화한 뒤 비동기 워커가 발송합니다.

핵심 목표는 아래 4가지입니다.

- 동일 이벤트의 중복 등록과 중복 처리를 방지할 것
- 발송 실패가 API 요청 흐름에 직접 전파되지 않도록 분리할 것
- 일시적 실패에 대해 재시도와 최종 실패 처리가 가능할 것
- 서버 재시작 및 다중 워커 환경에서도 미처리 요청을 다시 처리할 수 있을 것

상세 설계 문서:

- 비동기 처리 구조 / 재시도 정책: [docs/async-and-retry.md](./docs/async-and-retry.md)
- 요구사항 해석 / 개선 의견: [docs/improvements.md](./docs/improvements.md)

---

# 기술 스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어 / 런타임 | Java 21 | 과제 구현과 테스트에 사용한 JDK 버전 |
| 프레임워크 | Spring Boot 3.5 | 과제 필수 스택 |
| ORM | Spring Data JPA + Hibernate 6 | 도메인 모델과 상태 전이 중심 구현에 적합 |
| DB | PostgreSQL 16 | `FOR UPDATE SKIP LOCKED` 기반 다중 워커 동시성 검증을 위해 선택 |
| 테스트 | JUnit 5, Spring Boot Test, Testcontainers | PostgreSQL 실환경에 가까운 동시성 테스트 수행 |
| 빌드 | Gradle | Spring Boot 기본 빌드 도구 |

---

# 실행 방법

## 사전 요구사항

- JDK 21
- Docker

## 애플리케이션 실행

리포지토리 루트에서:

```bash
docker compose -f backend/docker-compose.yml up -d postgres
cd backend
./gradlew bootRun
```

- 기본 프로필은 `local`
- PostgreSQL 접속 정보
  - DB: `liveclass`
  - user: `liveclass`
  - password: `liveclass`
- 애플리케이션 기본 주소: `http://localhost:8080`

## 테스트 실행 방법

```bash
cd backend
./gradlew test
```

- 컨트롤러 / 단위 테스트는 H2 기반으로 실행됩니다.
- 동시성 / 워커 / 스턱 복구 테스트는 Testcontainers가 PostgreSQL 컨테이너를 자동으로 띄웁니다.
- 따라서 테스트 실행에도 Docker가 필요합니다.

---

# 요구사항 해석 및 가정

- 이 시스템은 `알림 도메인` 자체만 구현합니다. 수강 신청, 결제 등 원 비즈니스 이벤트의 발생 주체는 블랙박스로 두었습니다.
- 호출자는 `recipientId`, `notificationType`, `referenceId`, `channel`, `payload`, `scheduledAt`만 넘기면 됩니다.
- 알림 타입은 과제 예시에 맞춰 4종으로 제한했습니다.
  - `ENROLLMENT_CONFIRMED`
  - `PAYMENT_CONFIRMED`
  - `CLASS_STARTING_SOON`
  - `ENROLLMENT_CANCELLED`
- 실제 이메일 발송은 하지 않고 로그 출력으로 대체했습니다.
- 인증 / 인가는 과제 가이드에 따라 단순화했습니다. 현재는 `recipientId` 자체를 요청 값으로 받습니다.
- 읽음 처리는 의미적으로 `IN_APP` 채널에만 허용했습니다. `EMAIL`에 대한 읽음 처리 요청은 `400`으로 막았습니다.

---

# 설계 결정과 이유

## 1. DB에 알림 요청을 저장하고 워커가 처리하는 구조

메시지 브로커 없이도 요구사항을 만족해야 하므로, `notification` 테이블에 알림 요청을 저장하고 워커가 이를 가져가 처리하도록 구성했습니다.  
API 요청은 알림 요청을 `PENDING`으로 저장하고 즉시 반환하며, 실제 발송은 별도 워커가 처리합니다.

이 구조로 얻는 효과는 다음과 같습니다.

- 요청 접수와 발송을 분리해 발송 실패가 API 응답 흐름에 직접 영향을 주지 않음
- 서버 재시작 후에도 DB에 남은 작업을 다시 처리할 수 있음
- 브로커 없이도 재시도, 상태 조회, 실패 이력 추적이 가능함

## 2. 트랜잭션 경계 분리

트랜잭션은 크게 3구간으로 나눴습니다.

- claim: `PENDING` 행을 짧은 트랜잭션으로 `PROCESSING` 전환
- send: 외부 발송 호출은 트랜잭션 밖에서 수행
- record: 성공/실패 결과를 다시 짧은 트랜잭션으로 반영

이렇게 나눈 이유는 외부 채널 지연이 DB 락이나 커넥션 점유로 번지지 않게 하기 위해서입니다.

## 3. 멱등성

중복 등록 방지의 핵심 장치는 DB unique 제약입니다.

- `UNIQUE(recipient_id, notification_type, reference_id, channel)`

서비스는 먼저 선조회로 happy path를 처리하지만, 동시 요청 충돌은 DB 제약으로 방어합니다.  
동시 등록으로 `DataIntegrityViolationException`이 나면 기존 행을 다시 조회해 멱등 응답을 반환합니다.

## 4. 다중 워커 안전성

다중 인스턴스 환경에서 같은 알림을 동시에 집지 않도록 아래 조합을 사용했습니다.

- PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`
- `worker_id` 저장
- 발송 직전 / 결과 반영 시점의 ownership 재검증

즉, claim 시점과 result 시점 모두에서 “이 row가 아직 내 것인지”를 확인하도록 했습니다.

## 5. 전달 보장 모델

현재 보장 모델은 `exactly-once`가 아니라 `at-least-once`입니다.

중복 가능성이 남는 핵심 윈도우는 하나입니다.

- 외부 발송은 성공했지만
- 직후 `recordSuccess` DB 반영이 실패한 경우

이 경우 row는 `PROCESSING`으로 남고, 이후 stuck recovery가 다시 `PENDING`으로 돌리면 재발송이 가능해집니다.  
이 한계는 외부 채널 idempotency key 없이 내부 DB 상태만으로 해소하기 어렵습니다. 과제 범위에서는 이 한계를 숨기기보다, 어디서 왜 발생하는지 명시하는 쪽을 택했습니다.

## 6. stuck recovery 정책

`PROCESSING` 상태가 일정 시간 이상 지속되면 스턱으로 간주하고 재처리 가능 상태로 되돌립니다.

- 기본 임계치: 5분
- 복구 시 `retry_count`를 1 증가
- 한도 도달 시 `DEAD_LETTER`

이 정책은 워커 크래시와 `send 성공 / recordSuccess 실패`를 DB 상태만으로 구분할 수 없다는 점을 전제로 합니다.  
정확한 원인 판별보다 “무한 재처리 루프를 제한하고 한도 초과 시 종착시키는 것”을 우선했습니다.

---

# API 목록 및 예시

기준 URL: `http://localhost:8080`

## 1. 알림 발송 요청 등록

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "user-123",
    "notificationType": "ENROLLMENT_CONFIRMED",
    "referenceId": "enrollment-456",
    "channel": "EMAIL",
    "payload": {
      "courseTitle": "Spring Boot 입문",
      "startDate": "2026-05-01"
    },
    "scheduledAt": null
  }'
```

신규 등록 시 `202 Accepted`

```json
{
  "id": 1,
  "status": "PENDING",
  "createdAt": "2026-04-25T10:00:00",
  "duplicate": false
}
```

동일 키 재요청 시 `200 OK`

```json
{
  "id": 1,
  "status": "PENDING",
  "createdAt": "2026-04-25T10:00:00",
  "duplicate": true
}
```

## 2. 알림 단건 조회

```bash
curl http://localhost:8080/api/notifications/1
```

- 존재하면 `200 OK`
- 없으면 `404 NOTIFICATION_NOT_FOUND`

## 3. 사용자 알림 목록 조회

```bash
curl "http://localhost:8080/api/notifications?recipientId=user-123&status=PENDING&channel=EMAIL&type=ENROLLMENT_CONFIRMED&read=false&page=0&size=20"
```

- `recipientId`는 필수
- `status`, `channel`, `type`, `read`, `page`, `size`는 선택

## 4. 수동 재시도

```bash
curl -X POST http://localhost:8080/api/notifications/1/retry \
  -H "Content-Type: application/json" \
  -d '{ "resetRetryCount": false }'
```

- `DEAD_LETTER` 상태에서만 허용
- body 생략 시 `resetRetryCount=false`

## 5. 읽음 처리

```bash
curl -X POST http://localhost:8080/api/notifications/1/read
```

- `IN_APP` 채널만 허용
- `EMAIL` 채널이면 `400 READ_NOT_SUPPORTED_FOR_CHANNEL`
- 동시 요청 시 `read_at = COALESCE(read_at, now())`로 최초 시각만 유지

---

# 데이터 모델 설명

## `notification`

알림 요청 본체이자 비동기 처리 대상입니다.

주요 컬럼:

- `recipient_id`, `notification_type`, `reference_id`, `channel`
- `status`
- `payload`
- `rendered_title`, `rendered_body`
- `retry_count`, `max_retry`, `next_attempt_at`
- `processing_started_at`, `worker_id`
- `scheduled_at`
- `last_error`
- `read_at`
- `sent_at`, `created_at`, `modified_at`

상태:

- `PENDING`
- `PROCESSING`
- `SENT`
- `DEAD_LETTER`

핵심 인덱스 / 제약:

- `UNIQUE(recipient_id, notification_type, reference_id, channel)`
- `INDEX(status, next_attempt_at)`
- `INDEX(status, processing_started_at)`
- `INDEX(recipient_id, created_at)`

## `notification_attempt`

각 발송 시도 이력을 남기는 감사 로그입니다.

주요 컬럼:

- `notification_id`
- `attempt_no`
- `started_at`
- `finished_at`
- `result`
- `error_message`

제약:

- `UNIQUE(notification_id, attempt_no)`

---

# 테스트 실행 방법

```bash
cd backend
./gradlew test
```

주요 테스트는 아래 시나리오를 검증합니다.

- 동시 등록 시 unique 제약 기반 멱등성
- PostgreSQL `SKIP LOCKED` 기반 다중 워커 경쟁 제어
- 발송 실패 후 재시도 / 최종 `DEAD_LETTER`
- `recordSuccess` 실패 시 실패 경로로 잘못 되돌리지 않는지
- ownership mismatch 시 stale worker 응답 무시
- stuck recovery 및 한도 초과 시 `DEAD_LETTER` 전이
- 동시 읽음 처리의 최초 시각 유지
- 예약 발송 미도래 / 도래 시 동작

---

# 미구현 / 제약사항

## 운영 전 보강이 필요한 항목

- 외부 채널 idempotency key
- timeout / circuit breaker / time limiter
- `Retriable` / `Permanent` 예외 분류
- `DEAD_LETTER` 보존 및 아카이빙 정책
- 메트릭 / 알림 연동

## 현재 범위에서 단순화한 항목

- 실제 이메일 발송 미구현
- 인증 / 인가 미구현
- 메트릭, 대시보드, 알람 연동 미구현
- 페이지 크기 상한 검증 미구현
- `notification_attempt.notification_id` FK 및 삭제 정책 미구현

---

# AI 활용 범위

- 설계 검토: 트랜잭션 경계, 멱등성, stuck recovery 정책
- 코드 리뷰: 실패 시나리오와 동시성 이슈 반복 점검
- 문서 정리: README와 보조 문서 구조 보완

단, 코드와 문서는 모두 직접 수정하고 테스트 결과를 확인한 뒤 반영했습니다.
