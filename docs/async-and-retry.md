# 비동기 처리 구조 및 재시도 정책

## 1. 전체 흐름

```text
POST /api/notifications
  -> notification 저장 (PENDING)
  -> 202 Accepted 반환

워커 polling
  -> claim
  -> render
  -> send
  -> success / failure 기록
```

API 요청 스레드는 알림 요청을 저장만 하고 바로 반환합니다.  
실제 발송은 워커가 별도로 처리하므로, 발송 실패가 호출자 트랜잭션에 직접 전파되지 않습니다.

---

## 2. 트랜잭션 경계

### Tx A: claim

- `PENDING`
- `next_attempt_at <= now()`

조건의 row를 `SELECT ... FOR UPDATE SKIP LOCKED`로 가져온 뒤, 같은 트랜잭션에서 `PROCESSING`으로 바꿉니다.

```sql
SELECT id
FROM notification
WHERE status = 'PENDING'
  AND next_attempt_at <= :now
ORDER BY next_attempt_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

이후 같은 트랜잭션에서:

- `status = PROCESSING`
- `processing_started_at = now`
- `worker_id = :workerId`

를 기록하고 커밋합니다.

### Tx outside: send

외부 발송은 DB 트랜잭션 밖에서 수행합니다.

- `fetchOwnedForProcessing`
- `renderer.render`
- `dispatcher.send`

이 구간을 트랜잭션 밖에 둔 이유는 외부 채널 지연이 행 락이나 DB 커넥션 점유로 번지지 않게 하기 위해서입니다.

### Tx B: result

발송이 끝나면 다시 짧은 트랜잭션에서 결과를 반영합니다.

- 성공: `recordSuccess`
- 실패: `recordFailure`

이때도 `worker_id`를 다시 확인해 stale worker가 남의 row를 덮어쓰지 못하게 합니다.

---

## 3. 상태 머신

```text
PENDING
  -> PROCESSING
  -> SENT

PENDING
  -> PROCESSING
  -> PENDING   (재시도)

PENDING
  -> PROCESSING
  -> DEAD_LETTER
```

상태 의미:

- `PENDING`: 등록 직후 또는 재시도 대기 상태
- `PROCESSING`: 특정 워커가 점유 중인 상태
- `SENT`: 발송과 결과 기록이 모두 끝난 상태
- `DEAD_LETTER`: 재시도 한도 초과로 더 이상 자동 재시도하지 않는 상태

수동 재시도는 `DEAD_LETTER -> PENDING` 전이를 허용합니다.

---

## 4. 재시도 정책

재시도는 지수 백오프를 사용합니다.

| 실패 전 retry_count | 다음 대기 시간 |
|---|---|
| 0 | 60초 |
| 1 | 120초 |
| 2 | 240초 |
| 3 | 480초 |
| 4 | 960초 |
| 5 이상 | 최대 1시간 상한 |

정책 요약:

- 기본 `maxRetry = 5`
- 실패 시 `retry_count` 증가
- `last_error` 기록
- `next_attempt_at` 갱신
- 한도 도달 시 `DEAD_LETTER`
- 각 시도는 `notification_attempt`에 별도 기록

---

## 5. 중복 방지

중복 방지는 세 층으로 나눴습니다.

### 1) 등록 멱등성

```text
UNIQUE(recipient_id, notification_type, reference_id, channel)
```

동일 알림 요청이 동시에 여러 번 들어와도 DB가 최종적으로 1건만 허용합니다.  
서비스는 unique 충돌 시 기존 row를 다시 조회해 멱등 응답을 반환합니다.

### 2) 다중 워커 claim 중복 방지

`FOR UPDATE SKIP LOCKED`로 이미 다른 워커가 점유한 row는 건너뜁니다.

### 3) stale worker 간섭 방지

`worker_id`를 두 번 확인합니다.

- 발송 직전
- 결과 반영 직전

즉, claim 이후 복구나 재점유가 일어난 경우 늦게 도착한 워커 응답은 무시됩니다.

---

## 6. stuck recovery

`PROCESSING` 상태가 일정 시간 이상 지속되면 stuck으로 간주합니다.

기본 임계치:

- `threshold-seconds = 300` (5분)

복구 시 동작:

- `retry_count = retry_count + 1`
- `worker_id = null`
- `processing_started_at = null`
- `next_attempt_at = now`
- `last_error = "stuck recovery: ..."`
- 한도 도달 시 `DEAD_LETTER`, 아니면 `PENDING`

이 정책은 보수적입니다.  
워커 크래시와 `send 성공 / recordSuccess 실패`를 DB 상태만으로는 구분할 수 없기 때문에, stuck recovery도 하나의 실패로 계산해 무한 루프를 막는 쪽을 선택했습니다.

---

## 7. 서버 재시작 / 다중 인스턴스 대응

- 알림 요청은 DB에 영속화되므로 서버 재시작 후에도 유실되지 않습니다.
- `PENDING`은 재기동된 워커가 다시 처리합니다.
- 재시작 시점에 남아 있던 `PROCESSING`은 sweeper가 임계치 이후 복구합니다.
- 다중 인스턴스에서는 `SKIP LOCKED + worker_id` 조합으로 동일 row 중복 처리를 줄입니다.

---

## 8. 현재 보장 모델

현재 보장 모델은 `exactly-once`가 아니라 `at-least-once`입니다.

중복 가능성이 남는 핵심 윈도우는 다음입니다.

1. 외부 발송은 성공
2. 직후 `recordSuccess` DB 반영은 실패
3. row는 `PROCESSING`에 남음
4. stuck recovery가 다시 `PENDING`으로 복구
5. 이후 재발송 가능

즉, 내부 상태 관리만으로는 이 구간을 완전히 제거할 수 없습니다.  
과제 범위에서는 이 한계를 문서에 명시하고, 운영 환경에서는 외부 채널 idempotency key로 보강하는 방향을 전제로 했습니다.

---

## 9. 주요 설정

```yaml
notification:
  worker:
    poll-interval-ms: 1000
    batch-size: 50
    scheduling-enabled: true
  sweeper:
    poll-interval-ms: 60000
    threshold-seconds: 300
    scheduling-enabled: true
```
