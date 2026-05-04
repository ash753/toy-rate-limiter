# Step 7. Kubernetes 배포 - 테스트 계획

## 현재 진행 상태

- [ ] 이미지 빌드 및 동작 확인
- [ ] K8s 리소스 배포 상태 확인 (Resources Requests/Limits 준수 여부)
- [ ] nGrinder 컨트롤러 및 에이전트 연결 확인
- [ ] **Ingress를 통한 E2E 트래픽 및 IP 보존 확인**
- [ ] HA (High Availability) 테스트 (**rate-limiter 대상**)
- [ ] 모니터링 연동 확인

## 테스트 목적

Docker Desktop Kubernetes 환경에서 시스템을 구동하고, 제한된 리소스(10 CPU, 7GB RAM) 내에서 각 컴포넌트가 정상 동작하는지 확인한다. 특히 Ingress를 통한 IP 보존과 게이트웨이(`rate-limiter`)의 고가용성을 검증한다.

## 테스트 항목

### 1. 리소스 할당 및 상태 확인

- `kubectl describe pods`를 통해 각 파드에 설정된 CPU/Memory Limits가 요구사항(requirement.md)과 일치하는지 확인.
- `kubectl top pods` (Metrics Server 필요)를 통해 실제 리소스 사용량 모니터링.

### 2. Ingress 접속 확인

- `/etc/hosts` 설정 후 `http://rate-limiter.local` 접속 시 `test-api` 응답이 오는지 확인.
- `http://ngrinder.local` 접속 시 nGrinder UI가 나타나는지 확인.

### 3. IP 보존 및 차단 테스트 (핵심)

- **테스트 방법**: `X-Forwarded-For` 헤더를 수동으로 조작하거나 다양한 클라이언트에서 요청을 보내 IP별 차단이 독립적으로 작동하는지 확인.
- **로그 확인**: `rate-limiter` 파드의 로그에서 추출된 Client IP가 실제 요청자의 IP인지 확인.

### 4. nGrinder 동작 확인

- nGrinder에서 Ingress 호스트 주소를 타겟으로 부하 테스트 실행.
- Agent 2개가 분산하여 트래픽을 생성하는지 확인.

### 5. 고가용성 (HA) 테스트

- **rate-limiter**: `replica 2`로 구성되어 있으므로 파드 하나를 삭제했을 때 서비스 중단 없이 다른 파드로 트래픽이 즉시 전환되는지 확인.
- **test-api**: `replica 1`이므로 삭제 시 일시적인 서비스 중단이 발생하는지 확인 (의도된 설계).

### 6. 모니터링 연동 확인

- Grafana 대시보드에서 각 파드의 리소스 사용량 및 `rate-limiter` 메트릭이 정상적으로 수집되는지 확인.
