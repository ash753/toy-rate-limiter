# Step 3. Redis 연동 + Lua 스크립트 - 요구사항

## 목표

이동 윈도우 카운터 알고리즘(두 개의 fixed window 가중 평균)을 Redis Lua 스크립트로 구현하고, 원자적으로 동작하는 `RateLimiter` 서비스를 제공한다.

## 범위

- Spring Data Redis (Lettuce) 연동, Reactive 클라이언트 사용
- Redis 타임아웃 500ms
- Lua 스크립트로 원자적 체크/증가
- 시간 기준: Redis 서버 시간 (`redis.call('TIME')`)
- TTL: 윈도우 크기의 2배 (120초)

## 알고리즘

- 윈도우 크기: 60초 (1분)
- 두 개의 fixed window 가중 평균하여 "현재 유효 요청 수" 추정

### 계산식

```
나누기 단위: 60초 고정 윈도우
current_bucket = now_sec / 60
previous_bucket = current_bucket - 1
elapsed_in_current = now_sec % 60
weight_prev = (60 - elapsed_in_current) / 60

estimated_count = prev_count * weight_prev + current_count

if estimated_count >= limit: 차단
else: current_count += 1, 허용
```

## Lua 스크립트 입출력

### 입력
- `KEYS[1]`: 키 prefix (예: `rl:/api/foo`)
- `ARGV[1]`: limit (정수)
- `ARGV[2]`: window size (초, 기본 60)
- `ARGV[3]`: TTL (초, 기본 120)

### 반환
```
{allowed (1/0), remainCount (정수), retryAfterSeconds (정수, 다음 윈도우 시작까지 남은 시간)}
```
- **Retry-After 전략:** 다음 윈도우가 시작될 때까지의 잔여 시간을 반환하여 클라이언트의 즉각적인 재시도를 억제하고 전체 시스템(Gateway, Redis)의 부하를 보호함.

### 시간 기준
- 스크립트 내부에서 `redis.call('TIME')`로 현재 시간 획득
- **결정: Lua 내부에서 `TIME`으로 bucket 계산 + 키 suffix 생성** (키 prefix만 받음)
- 이유: 멀티 인스턴스 시계 편차 제거, 단일 source of truth

## Lua 스크립트 로직 (`sliding_window.lua`)

```lua
local prefix = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1])
local current_bucket = math.floor(now / window)
local previous_bucket = current_bucket - 1
local elapsed = now % window
local weight = (window - elapsed) / window

local cur_key = prefix .. ':' .. current_bucket
local prev_key = prefix .. ':' .. previous_bucket

local cur_count = tonumber(redis.call('GET', cur_key) or '0')
local prev_count = tonumber(redis.call('GET', prev_key) or '0')

local estimated = prev_count * weight + cur_count

if estimated >= limit then
  local retry_after = window - elapsed
  return {0, 0, retry_after}
end

redis.call('INCR', cur_key)
redis.call('EXPIRE', cur_key, ttl)
local remainCount = math.floor(limit - estimated - 1)
return {1, remainCount, 0}
```

## 도메인 모델

```kotlin
data class RateLimitResult(
  val allowed: Boolean,
  val remainCount: Int,
  val retryAfterSeconds: Int
)
```

## 서비스 인터페이스

```kotlin
interface RateLimiter {
  fun check(keyPrefix: String, limit: Int): Mono<RateLimitResult>
}
```

## 검증 기준

- 단일 스레드에서 limit 초과 시 차단 동작
- 윈도우 경계 넘어가면 카운터 리셋
- 멀티 스레드 동시 호출에서도 카운팅이 정확 (Lua 원자성)
- Testcontainers로 Redis 띄워 통합 테스트 통과
