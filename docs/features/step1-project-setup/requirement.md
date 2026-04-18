# Step 1. 프로젝트 초기 구성 - 요구사항

## 목표

두 Spring 서버와 Redis가 로컬에서 띄워지고, 서버 1이 서버 2로 요청을 포워딩하는 최소 동작 상태를 만든다.

## 범위

- Gradle 멀티 모듈 구조
- 서버 1 (rate_limiter): Spring WebFlux 기반 게이트웨이
- 서버 2 (test_api): Spring Web (MVC) 기반 테스트용 API
- Redis: 로컬 docker-compose로 기동
- 서버 1 → 서버 2 포워딩 (rate limit 없음)

## 기술 스택

- Kotlin + Spring Boot
- 서버 1: Spring WebFlux, Spring Actuator
- 서버 2: Spring Web, Spring Actuator
- Redis (docker-compose로 로컬 구동)
- Gradle (Kotlin DSL)

## 결과물

- `:rate-limiter`, `:test-api` 두 개의 모듈
- `docker-compose.yml` (Redis)
- 서버 2: `GET /api/foo`, `GET /api/bar` 엔드포인트
  - **Best Practice 공통 응답 규격(`ApiResponse`) 적용** (UTC timestamp, status, message)
  - `/api/foo` -> `data: "foo"`
  - `/api/bar` -> `data: "bar"`
- 서버 1: 들어온 요청을 `proxy.routes` 설정에 따라 해당 서비스로 릴레이하는 WebClient 기반 포워딩

## 제약사항

- 아직 rate limit 로직은 포함하지 않음 (Step 3, 4에서 추가)
- 포워딩은 path, method, query, headers, body를 있는 그대로 전달

## 검증 기준

- `docker-compose up -d redis`로 Redis 기동 성공
- 두 서버 모두 기동 성공
- `curl localhost:8080/api/foo` → 서버 2의 JSON 응답을 서버 1을 통해 받음
