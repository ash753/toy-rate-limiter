# Step 7. Kubernetes 배포 - 실행 계획

## 현재 진행 상태

- [x] Dockerfile 작성 완료 (모듈별 독립 빌드 구조)
- [x] 이미지 빌드 완료 (`rate-limiter:0.1.0`, `test-api:0.1.0`)
- [x] K8s Manifest 작성 완료 (Namespace, ConfigMap, Deployment, Service, Ingress)
- [x] nGrinder Manifest 작성 완료 (Controller, Agent)
- [ ] Helm을 통한 모니터링 스택 구축
- [x] 통합 배포 스크립트 작성 완료 (`deploy.sh`)

## 로컬 환경 설정 (Docker Desktop)

- **Kubernetes 활성화**: Docker Desktop 설정에서 Kubernetes 활성화 확인.
- **리소스 할당**: Docker Desktop 설정(Resources)에서 **CPU 10 Core, Memory 7 GB**로 설정 (최대 가용 리소스).

## 작업 순서

### 1. Dockerfile 작성 (완료)

... (기존 내용 동일) ...

### 2. 이미지 빌드 및 로컬 환경 준비

- 이미지 빌드: `./deploy.sh build` (또는 각 모듈에서 `docker build`)
- Docker Desktop Kubernetes 컨텍스트 확인: `kubectl config use-context docker-desktop`

### 3. Kubernetes Manifest 작성 및 수정

#### 3-1. Namespace
`k8s/namespace.yaml` 생성

#### 3-2. ConfigMap
- `k8s/rate-limiter/configmap.yaml`: `X-Forwarded-For`를 신뢰하기 위한 Spring 설정 포함.
  ```yaml
  server:
    forward-headers-strategy: native
  ```

#### 3-3. 서비스 배포 및 리소스 제한 적용
각 Deployment 매니페스트에 요구사항에 정의된 **Resources (Requests/Limits)** 설정을 추가한다.
- **rate-limiter**: 2 replicas, CPU 1.5/Memory 1GB Limit
- **test-api**: **1 replica**, CPU 1.0/Memory 512MB Limit
- **Redis**: 1 Instance, CPU 1.0/Memory 512MB Limit

#### 3-4. Ingress (IP 보존 핵심)
- `k8s/ingress.yaml`:
  - `rate-limiter.local` -> `rate-limiter` 서비스 연결
  - `ngrinder.local` -> `ngrinder-controller` 서비스 연결
  - **Annotations**: `nginx.ingress.kubernetes.io/use-forwarded-headers: "true"` 등 설정

#### 3-5. nGrinder
- **ngrinder-controller**: Deployment (1 replica), CPU 2.0/Memory 1.5GB
  - **데이터 보존**: `PersistentVolumeClaim`을 사용하여 `/opt/ngrinder-controller` 데이터 영속성 확보.
- **ngrinder-agent**: Deployment (2 replicas), CPU 1.0/Memory 512MB

### 4. 모니터링 스택 (Helm)

... (기존 내용 동일) ...

### 5. 배포 및 검증

`deploy.sh` 실행 및 리소스 상태 확인:
1. `kubectl apply -f k8s/namespace.yaml`
2. `kubectl apply -f k8s/redis/`
3. `kubectl apply -f k8s/test-api/`
4. `kubectl apply -f k8s/rate-limiter/`
5. `kubectl apply -f k8s/ngrinder/`
6. `kubectl apply -f k8s/ingress.yaml`
7. `helm upgrade --install monitoring ...`
8. `kubectl get pods -n rate-limiter` 명령으로 리소스 할당 상태 확인.

## 예상 파일 구조

```
toy-rate-limiter/
├── rate-limiter/Dockerfile (완료)
├── test-api/Dockerfile (완료)
├── k8s/
│   ├── namespace.yaml
│   ├── ingress.yaml (Ingress + IP 보존 설정)
│   ├── rate-limiter/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── configmap.yaml
│   ├── test-api/ ...
│   ├── redis/ ...
│   ├── ngrinder/
│   │   ├── controller-deployment.yaml
│   │   ├── controller-service.yaml
│   │   └── agent-deployment.yaml
│   └── monitoring/
│       ├── servicemonitor.yaml
│       └── prometheus-values.yaml
└── deploy.sh
```

## 주의사항

1. **Ingress Controller 설치**: 클러스터에 NGINX Ingress Controller 등이 미리 설치되어 있어야 함.
2. **호스트 파일 설정**: 로컬 테스트 시 `/etc/hosts`에 `rate-limiter.local`, `ngrinder.local` 등록 필요.
3. **X-Forwarded-For 신뢰**: Spring Boot 애플리케이션에서 `server.forward-headers-strategy: native` 설정을 통해 Ingress가 전달한 헤더를 올바르게 처리해야 함.
