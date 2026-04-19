# 처리율 제한 장치(Rate Limiter) 토이 프로젝트

## 프로젝트 개요

이 프로젝트는 분산 환경에서 동작하는 처리율 제한 장치(Rate Limiter)를 구현한 토이 프로젝트입니다. Gradle 멀티 모듈 구조를 가지며, Kotlin과 Spring Boot를 기반으로 설계되었습니다.

전체 시스템은 크게 세 가지 구성 요소로 나뉩니다:

1.  **`rate-limiter` (게이트웨이/프록시):** Spring WebFlux 기반의 API 게이트웨이입니다. 모든 요청을 가로채서 Redis를 이용해 처리율 제한 정책을 적용하고, 허용된 요청만 백엔드 서비스로 전달합니다.
    *   **처리율 제한 알고리즘:** Redis Lua 스크립트를 사용하여 원자성을 보장하는 이동 윈도우 카운터(Moving Window Counter) 알고리즘을 구현했습니다.
    *   **라우팅:** 경로 기반 라우팅(Path-based Routing)을 통해 특정 패턴(예: `/api/**`)의 요청을 백엔드로 포워딩합니다.
    *   **복구 전략(Resilience):** Resilience4j Circuit Breaker를 도입하여 Redis 장애 시에도 서비스가 중단되지 않도록 하는 Fail-Open 전략을 적용했습니다.
2.  **`test-api` (백엔드 서비스):** 프록시의 동작을 테스트하기 위한 간단한 Spring MVC 애플리케이션입니다.
3.  **Redis:** 분산 인스턴스 간의 카운터 정보를 공유하기 위한 중앙 저장소로 사용됩니다.

## 기술 스택

*   **언어:** Kotlin
*   **프레임워크:** Spring Boot (Gateway: WebFlux, Backend: Web MVC)
*   **저장소:** Redis
*   **회복성:** Resilience4j (Circuit Breaker)
*   **모니터링:** Spring Boot Actuator, Micrometer (Prometheus)
*   **빌드 도구:** Gradle (Kotlin DSL)
*   **인프라:** Docker Compose (로컬 Redis), Kubernetes (배포 계획 중)

## 빌드 및 실행 방법

### 사전 준비
*   Java 21
*   Docker & Docker Compose (Redis 실행용)

### 로컬 실행 순서

1.  **Redis 시작:**
    ```bash
    docker-compose up -d redis
    ```

2.  **Test API 서버 실행 (Backend):**
    ```bash
    ./gradlew :test-api:bootRun
    ```
    서버가 `8081` 포트에서 실행됩니다.

3.  **Rate Limiter 서버 실행 (Gateway):**
    ```bash
    ./gradlew :rate-limiter:bootRun
    ```
    서버가 `8080` 포트에서 실행됩니다. 기본적으로 `local` 프로파일이 활성화되어 있어 `/api/**` 요청을 `localhost:8081`로 전달합니다.

4.  **동작 확인:**
    프록시 서버로 요청을 보내 백엔드까지 정상 전달되는지 확인합니다.
    ```bash
    curl -i http://localhost:8080/api/foo
    ```

### 프로젝트 빌드
실행 가능한 JAR 파일을 생성하려면 다음 명령을 사용합니다:
```bash
./gradlew build
```

## 프로젝트 구조 및 아키텍처

*   `rate-limiter/`: 게이트웨이 서비스 모듈.
    *   `src/main/kotlin/com/ratelimiter/proxy/`: 요청 차단 및 포워딩을 담당하는 `ProxyWebFilter`와 라우팅 우선순위를 처리하는 `RouteMatcher`가 포함되어 있습니다.
    *   `src/main/kotlin/com/ratelimiter/config/`: `application.yaml` 설정을 읽기 위한 설정 클래스들이 위치합니다.
    *   `src/main/resources/`: 공통 설정(`application.yaml`)과 로컬 개발용 설정(`application-local.yaml`)이 포함되어 있습니다.
*   `test-api/`: 테스트용 백엔드 서비스 모듈.
*   `docs/`: 상세 요구사항, 단계별 구현 계획, 테스트 계획 문서들이 포함되어 있습니다.

## 개발 컨벤션 및 규칙

*   **공통 응답 포맷:** 각 서비스가 직접 응답을 생성하는 경우(백엔드 비즈니스 로직 또는 게이트웨이 자체 에러/차단 발생 등) `ApiResponse` 클래스를 사용하여 일관된 JSON 규격을 유지합니다. 게이트웨이는 정상적인 포워딩 시 백엔드의 응답을 그대로 전달합니다.
*   **설정 프로파일:** 로컬 개발 시에는 `application-local.yaml`을 사용하며, 메인 설정 파일에서 기본적으로 `local` 프로파일이 활성화되도록 구성되어 있습니다.
*   **라우팅 우선순위:** `RouteMatcher`는 Spring의 `PathPattern.SPECIFICITY_COMPARATOR`를 사용하여 더 구체적인 경로 패턴(예: `/api/foo`)이 일반적인 패턴(예: `/api/**`)보다 먼저 매칭되도록 보장합니다.
*   **헤더 관리:** 요청 포워딩 시 기존의 `X-Forwarded-*` 헤더를 보존하여 클라이언트 정보를 유지하며, `Host` 헤더는 목적지 서버에 맞게 `WebClient`가 자동으로 재설정합니다.

## AI 에이전트 지침

*   **파일 관리:** Gemini가 새로 추가하거나 수정한 파일은 반드시 `git add`를 수행하여 스테이징 상태로 만듭니다.
*   **문서 동기화:** 소스 코드의 변경 사항이 발생할 경우, 해당 변경 내용이 `@docs/**` 하위의 관련 문서(요구사항, 설계, 테스트 계획 등)에도 반드시 반영되어 최신 상태를 유지해야 합니다.
