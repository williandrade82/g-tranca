# Deploy do jogo online (cluster local)

Sempre use o contexto do Docker Desktop de forma explicita (`--context docker-desktop` / `--kube-context docker-desktop`).
Ative antes: Docker Desktop, Settings, Kubernetes, Enable. Confira: `kubectl --context docker-desktop get nodes` (no Ready).

Rode a partir da pasta `online/`.

```bash
# 1. Imagem local (o Docker Desktop K8s enxerga as imagens locais; sem registro externo)
docker build -f game-service/Dockerfile -t gtranca/game-service:dev .

# 2. Ingress (Traefik) - uma vez
helm repo add traefik https://traefik.github.io/charts && helm repo update
helm install traefik traefik/traefik -n traefik --create-namespace --kube-context docker-desktop -f deploy/infra/traefik-values.yaml

# 3. game-service
helm upgrade --install game-service deploy/helm/game-service -n gtranca --create-namespace \
  --kube-context docker-desktop --set ingress.enabled=true

# 4. Conferir
curl http://192.168.1.5/health        # via ingress
kubectl --context docker-desktop -n gtranca port-forward svc/game-service 8080:80   # alternativa: http://localhost:8080/health

# 5. Infra (so quando for usada; senha do Postgres em deploy/infra/postgres.yaml deve ser trocada)
kubectl --context docker-desktop apply -f deploy/infra/postgres.yaml
helm repo add nats https://nats-io.github.io/k8s/helm/charts/
helm install nats nats/nats -n gtranca --kube-context docker-desktop -f deploy/infra/nats-values.yaml
```

Desenvolvimento local sem cluster: `.\gradlew.bat :game-service:run` e `http://localhost:8080/health`.
