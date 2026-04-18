# Step 1. 프로젝트 초기 구성 - 실행 계획 (독립 프로젝트 방식)

## 작업 순서

### 1. 독립적인 Gradle 프로젝트 구성
- `rate-limiter`와 `test-api` 각각 별도의 루트를 가지는 독립적인 프로젝트로 구성
- 각 프로젝트 디렉토리에 `settings.gradle.kts`, `build.gradle.kts`, `gradlew` 등을 개별적으로 유지
- **루트 `.gitignore` 통합 관리**: 서브 프로젝트의 중복된 `.gitignore`를 삭제하고 루트에서 전역적으로 관리

### 2. 서버 2 (test-api) 구현
- 의존성: `spring-boot-starter-web`, `spring-boot-starter-actuator`
- 포트: 8081
- **Best Practice 공통 응답 규격 구현**:
  - `common/Constants.kt`: `UTC` 상수 관리
  - `common/ApiResponse.kt`: `ZonedDateTime` 기반 공통 규격 (UTC 반영)
- **컨트롤러 구현 (`SampleController`)**:
  - `GET /api/foo` → `ApiResponse.success("foo")`
  - `GET /api/bar` → `ApiResponse.success("bar")`

### 3. 서버 1 (rate-limiter) 구현
- 의존성: `spring-boot-starter-webflux`, `spring-boot-starter-actuator`
- 포트: 8080
- `WebClient` 빈 설정: `test-api` 등 각 서비스 호출용
- **포워딩 로직 (WebFilter)**:
  - **경로 기반 라우팅 (Path-based Routing)**:
    - `application.yml`의 `proxy.routes` 설정을 읽어 요청 경로 패턴에 맞는 `target-uri` 결정
    - 매칭되는 라우트가 없을 경우 `chain.filter(exchange)`를 호출하여 내부 핸들러(예: Actuator)로 전달
  - 원본 요청의 method, path, query, headers, body를 그대로 릴레이
    - **헤더 관리**: 기존 `X-Forwarded-*` 헤더가 있다면 보존하여 전달 (단, `Host` 헤더는 타겟 서버로 재설정됨)
  - 응답의 status, headers, body를 손실 없이 반환

### 4. docker-compose.yml
- 프로젝트 최상단 위치
```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

### 5. application.yml (서버 1 예시)

```yaml
server:
  port: 8080
spring:
  data:
    redis:
      host: localhost
      port: 6379
proxy:
  routes:
    - path-pattern: "/api/**"
      target-uri: "http://localhost:8081"
```

## 예상 파일 구조

```
toy-rate-limiter/
├── .gitignore            # 통합 관리
├── docker-compose.yml
├── rate-limiter/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/com/ratelimiter/
│       │   ├── RateLimiterApplication.kt
│       │   ├── config/ProxyRoutesProperties.kt
│       │   ├── config/WebClientConfig.kt
│       │   └── proxy/ProxyWebFilter.kt
│       └── resources/application.yaml
└── test-api/
    ├── build.gradle.kts
    └── src/main/
        ├── kotlin/com/testapi/
        │   ├── TestApiApplication.kt
        │   ├── common/Constants.kt
        │   ├── common/ApiResponse.kt
        │   └── controller/SampleController.kt
        └── resources/application.yaml
```

## 주의사항

- **라우팅 우선순위**: 여러 패턴이 겹칠 경우 가장 구체적인 패턴이 먼저 매칭되도록 설계 필요
- **성능**: WebClient를 통해 비동기 논블로킹 방식으로 포워딩하여 대량 요청 처리 보장

## 완료 조건

- 두 서버 각각 독립적으로 빌드/기동 성공
- `curl localhost:8080/api/foo` 호출 시 설정된 라우팅 규칙에 따라 서버 2의 응답이 정상적으로 전달됨
