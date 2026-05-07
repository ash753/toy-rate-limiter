# Step 7. Kubernetes 배포 - 요구사항

## 현황 (Status)

- [x] **Dockerfile 작성**: `rate-limiter`, `test-api` 각각 멀티 스테이지 빌드 Dockerfile 작성 완료.
- [x] **Kubernetes Manifest**: `k8s/` 하위 매니페스트 작성 완료 (Namespace, ConfigMap, Deployment, Service, Ingress).
- [ ] **Helm 모니터링**: `kube-prometheus-stack` 설치 및 설정 필요.
- [x] **nGrinder 배포**: Controller 및 Agent를 K8s 내부에 배포하기 위한 매니페스트 작성 완료.
- [x] **배포 스크립트**: `deploy.sh` 작성 완료.

## 목표

전체 시스템(애플리케이션, 인프라, 모니터링, 부하 테스트 도구)을 **Docker Desktop Kubernetes** 환경에 배포해 프로덕션 유사 환경에서 동작시킨다.

## 리소스 할당 (Resource Constraints)

Docker Desktop에서 사용할 수 있는 전체 리소스를 다음과 같이 설정하고 각 컴포넌트에 배분한다.
- **총 할당 리소스**: CPU 10 Core, Memory 7 GB

### 컴포넌트별 리소스 배분 계획
| 컴포넌트 | Replicas | CPU (Request/Limit) | Memory (Request/Limit) | 합계 (Max Limit) |
| :--- | :---: | :--- | :--- | :--- |
| **rate-limiter** | 2 | 1.0 / 1.5 | 512MB / 1GB | 3.0 CPU, 2.0GB |
| **test-api** | 1 | 0.5 / 1.0 | 256MB / 512MB | 1.0 CPU, 0.5GB |
| **Redis** | 1 | 0.5 / 1.0 | 256MB / 512MB | 1.0 CPU, 0.5GB |
| **nGrinder Controller** | 1 | 1.0 / 2.0 | 1GB / 1.5GB | 2.0 CPU, 1.5GB |
| **nGrinder Agent** | 2 | 0.5 / 1.0 | 512MB / 512MB | 2.0 CPU, 1.0GB |
| **Monitoring (Helm)** | - | ~0.5 / 1.0 | ~512MB / 1.0GB | 1.0 CPU, 1.0GB |
| **계 (Total)** | | | | **10.0 CPU, 6.5GB** |

## 범위

- 각 서버 Docker 이미지 (`rate-limiter`, `test-api`)
- Kubernetes manifest (Deployment, Service, ConfigMap, **Ingress**)
- Redis 배포 (Single Instance)
- Prometheus, Grafana 배포 (Helm)
- **nGrinder 배포** (Controller 1, Agent 2)
- ConfigMap으로 application.yml 주입
- **실제 클라이언트 IP 보존 (Ingress + X-Forwarded-For)**

## 배포 구성

### rate-limiter (서버 1)
- **Deployment**: replica 2
- **Resources**: CPU 1.5 Limit, Memory 1GB Limit (per pod)
- **Service**: ClusterIP (Port 8080)
- **Ingress**: 외부 진입점, **X-Forwarded-For 전파 설정**
- **ConfigMap**: `application.yml` 주입

### test-api (서버 2)
- **Deployment**: **replica 1**
- **Resources**: CPU 1.0 Limit, Memory 512MB Limit
- **Service**: ClusterIP (Port 8081)

### Redis
- **Deployment**: 1 Instance
- **Resources**: CPU 1.0 Limit, Memory 512MB Limit
- **Service**: ClusterIP (Port 6379)

### 모니터링 (Helm)
- **kube-prometheus-stack** 차트 사용
- ServiceMonitor로 `rate-limiter` 스크레이핑 등록

### nGrinder (부하 테스트)
- **ngrinder-controller**: 
  - Deployment (1 replica)
  - Resources: CPU 2.0 Limit, Memory 1.5GB Limit
  - Ingress: 웹 UI 노출 (Port 9000 -> 80)
  - Service: ClusterIP (Port 16001, 12000-12009)
- **ngrinder-agent**:
  - Deployment (2 replicas)
  - Resources: CPU 1.0 Limit, Memory 512MB Limit (per pod)
  - 환경변수로 Controller 주소 설정

## Docker 이미지

- 베이스: `eclipse-temurin:21-jre-alpine`
- 이미지 이름: `rate-limiter:0.1.0`, `test-api:0.1.0`
- nGrinder 공식 이미지: `ngrinder/controller:3.5.3`, `ngrinder/agent:3.5.3`

## IP 보존

- **Ingress Controller (NGINX 등) 사용**:
  - `X-Forwarded-For` 헤더를 통해 실제 클라이언트 IP 전달.
  - Ingress 설정에서 `use-forwarded-headers: "true"` 적용.
  - 애플리케이션(`rate-limiter`)에서 해당 헤더를 신뢰하도록 설정.

## 검증 기준

- 모든 파드(App, Redis, Prometheus, Grafana, nGrinder) Running 상태
- Ingress 호스트를 통해 요청 → 서버 2 응답 수신
- nGrinder 웹 UI 접속 가능 및 Agent 연결 확인
- Grafana 대시보드에서 클러스터 및 애플리케이션 메트릭 조회 가능
- `X-Forwarded-For` 헤더로 실제 클라이언트 IP 식별 및 처리율 제한 정상 동작 확인
