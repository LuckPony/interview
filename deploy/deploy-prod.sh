#!/usr/bin/env bash
set -Eeuo pipefail

# Production redeploy that reuses local Docker images.
# Existing infrastructure images are never pulled again unless they are missing.
# Persistent volumes are never removed.

DEPLOY_DIR="${DEPLOY_DIR:-/opt/mianba}"
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.yml}"
BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
SKIP_BACKUP="${SKIP_BACKUP:-false}"

cd "$DEPLOY_DIR"

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose -f "$COMPOSE_FILE")
elif docker-compose version >/dev/null 2>&1; then
  COMPOSE=(docker-compose -f "$COMPOSE_FILE")
else
  echo "ERROR: docker compose/docker-compose is not installed" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  echo "ERROR: $DEPLOY_DIR/.env does not exist" >&2
  exit 1
fi

# Keep this list aligned with the image fields in docker-compose.prod.yml.
IMAGES=(
  "pgvector/pgvector:pg16@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b"
  "redis:7-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf"
  "minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"
  "minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727"
)

ensure_image() {
  local image="$1"
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "[reuse] $image"
  else
    echo "[pull missing] $image"
    docker pull "$image"
  fi
}

for image in "${IMAGES[@]}"; do
  ensure_image "$image"
done

"${COMPOSE[@]}" config >/dev/null

if [[ "$SKIP_BACKUP" != "true" ]] && "${COMPOSE[@]}" ps -q postgres | grep -q .; then
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR"
  timestamp="$(date +%Y%m%d-%H%M%S)"
  backup="$BACKUP_DIR/interview-before-deploy-$timestamp.dump"
  echo "[backup] $backup"
  "${COMPOSE[@]}" exec -T postgres sh -c \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup"
  chmod 600 "$backup"
fi

# Build only local application images. Docker reuses cached base images/layers and
# does not contact the registry because no --pull option is supplied.
echo "[build] backend web (local cache enabled)"
"${COMPOSE[@]}" build backend web

# --no-build prevents Compose from triggering an implicit rebuild. Compose uses
# already-present immutable infrastructure images and the two images built above.
echo "[up] postgres redis minio backend web"
"${COMPOSE[@]}" up -d --no-build postgres redis minio backend web

# Idempotently create the MinIO bucket. Exit 0 is expected for this one-shot job.
"${COMPOSE[@]}" up --no-build --no-deps minio-init

wait_healthy() {
  local name="$1"
  local tries="${2:-60}"
  local container
  container="$("${COMPOSE[@]}" ps -q "$name")"
  for ((i=1; i<=tries; i++)); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)"
    [[ "$status" == "healthy" || "$status" == "running" ]] && return 0
    sleep 2
  done
  echo "ERROR: $name is not healthy" >&2
  "${COMPOSE[@]}" logs --tail=100 "$name" >&2 || true
  return 1
}

wait_healthy postgres
wait_healthy redis
wait_healthy minio

for ((i=1; i<=60; i++)); do
  curl -fsS http://127.0.0.1:23333/actuator/health >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS http://127.0.0.1:23333/actuator/health >/dev/null
curl -fsS http://127.0.0.1:18080/actuator/health >/dev/null

"${COMPOSE[@]}" ps
echo "DEPLOY_OK"
