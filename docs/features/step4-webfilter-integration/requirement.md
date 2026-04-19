# Step 4. Rate Limit WebFilter 통합 - 요구사항

## 목표

서버 1(WebFlux)의 모든 요청이 `WebFilter`에서 rate limit 체크를 거친 뒤, 허용된 요청만 서버 2로 포워딩되도록 통합한다.

## 범위

- `RateLimitWebFilter` 구현 (rate limit 체크 전용)
- 포워딩 로직과의 순서 정리 (`@Order`)
- `IpExtractor` 유틸리티를 통한 클라이언트 IP 추출
- `RateLimitConstants`를 통한 응답 헤더 및 메시지 관리
- 429 응답 시 JSON body 반환

## 동작 흐름

```
요청 → [RateLimitWebFilter] → (허용) → [ProxyWebFilter] → 서버 2
                          ↘ (차단) → 429 응답 (체인 중단)
```

## WebFilter 동작 상세

1. 요청 path로 `RouteMatcher.match(path)` 호출 → 매칭되는 `RouteConfig` 및 설정된 limit 획득.
2. 매칭되는 경로가 없는 경우 `chain.filter(exchange)`로 즉시 다음 필터로 넘김.
3. 키 prefix 생성:
   - `per-ip=false`: `rl:{matchedPath}`
   - `per-ip=true`: `rl:{matchedPath}:{ip}`
4. `RateLimiter.check(keyPrefix, limit)` 호출 → `RateLimitResult` 획득.
5. 응답 헤더 설정 (`RateLimitConstants` 정의 값 사용):
   - 공통: `X-RateLimit-Limit`, `X-RateLimit-Remaining`
   - 차단 시: `Retry-After` (초 단위 정수)
6. 허용: `chain.filter(exchange)` 호출.
7. 차단: 429 StatusCode + JSON body (`{"error":"too many requests"}`) + 체인 중단.

## IP 추출 규칙 (`IpExtractor`)

- 우선순위: `X-Forwarded-For`의 첫 번째 값 → `X-Real-IP` → `exchange.request.remoteAddress`.
- `X-Forwarded-For: 1.2.3.4, 5.6.7.8` → `1.2.3.4` 사용.
- 전부 없으면 fallback: `"unknown"`.

## 응답 스펙

### 허용 (200 OK 등)
```
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 99
...
```

### 차단 (429 Too Many Requests)
```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
Retry-After: 30
Content-Type: application/json;charset=UTF-8

{"error":"too many requests"}
```
