#!/bin/bash
set -e

echo "Starting Deployment to Kubernetes..."

# 1. Namespace
echo "Applying Namespace..."
kubectl apply -f k8s/namespace.yaml

# 2. Redis
echo "Applying Redis..."
kubectl apply -f k8s/redis/

# 3. Test API
echo "Applying Test API..."
kubectl apply -f k8s/test-api/

# 4. Rate Limiter (ConfigMap + Deployment + Service)
echo "Applying Rate Limiter..."
kubectl apply -f k8s/rate-limiter/

# 5. nGrinder
echo "Applying nGrinder (Logback Config, PVC, Controller, Agent)..."
kubectl apply -f k8s/ngrinder/agent-logback-configmap.yaml
kubectl apply -f k8s/ngrinder/

# 6. Ingress
echo "Applying Ingress..."
kubectl apply -f k8s/ingress.yaml

# 7. Monitoring (Helm)
echo "Checking for Helm..."
if command -v helm &> /dev/null
then
    echo "Installing/Upgrading Monitoring Stack with Helm..."
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
    helm repo update
    helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
      --namespace monitoring --create-namespace \
      -f k8s/monitoring/prometheus-values.yaml
    
    echo "Applying ServiceMonitor and Dashboards..."
    kubectl apply -f k8s/monitoring/servicemonitor.yaml
    kubectl apply -f k8s/monitoring/grafana-dashboard-ratelimit.yaml
    kubectl apply -f k8s/monitoring/grafana-dashboard-jvm.yaml
else
    echo "Helm not found, skipping monitoring stack installation."
fi

echo "Deployment complete!"
kubectl get pods -n rate-limiter
