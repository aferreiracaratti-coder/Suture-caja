#!/usr/bin/env bash
# Levanta PostgreSQL descartable, prueba backend/frontend y recorre una jornada por API.
set -euo pipefail

cd "$(dirname "$0")/.."
for command in docker java mvn npm python3 curl openssl; do
  command -v "$command" >/dev/null || { echo "Falta $command" >&2; exit 1; }
done

sim_container="suture-caja-sim-$RANDOM"
sim_port="${CAJA_SIMULATION_DB_PORT:-55437}"
api_port="${CAJA_SIMULATION_API_PORT:-8096}"
db_password="$(openssl rand -hex 24)"
admin_password="$(openssl rand -hex 24)"
api_pid=""
cleanup() {
  if [[ -n "$api_pid" ]]; then kill "$api_pid" 2>/dev/null || true; wait "$api_pid" 2>/dev/null || true; fi
  docker rm -f "$sim_container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --rm -d --name "$sim_container" \
  -e POSTGRES_DB=caja_simulation -e POSTGRES_USER=caja \
  -e "POSTGRES_PASSWORD=$db_password" \
  -p "127.0.0.1:$sim_port:5432" postgres:16 >/dev/null
for _ in {1..40}; do
  if docker exec "$sim_container" pg_isready -U caja -d caja_simulation >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$sim_container" pg_isready -U caja -d caja_simulation >/dev/null
docker exec "$sim_container" psql -U caja -d postgres -c 'CREATE DATABASE caja_integration' >/dev/null

echo '== PostgreSQL real: pruebas backend =='
(
  cd backend
  CAJA_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$sim_port/caja_integration" \
  CAJA_TEST_DB_USERNAME=caja CAJA_TEST_DB_PASSWORD="$db_password" mvn test -q
  mvn -q -DskipTests package
)
echo '== Angular: tests y build =='
(cd frontend && npm test -- --watch=false && npm run build)

echo '== Jornada comercial por HTTP =='
CAJA_PORT="$api_port" \
CAJA_DB_URL="jdbc:postgresql://127.0.0.1:$sim_port/caja_simulation" \
CAJA_DB_USERNAME=caja CAJA_DB_PASSWORD="$db_password" \
CAJA_BOOTSTRAP_ORGANIZATION='Comercio simulado' \
CAJA_BOOTSTRAP_USERNAME=simadmin CAJA_BOOTSTRAP_PASSWORD="$admin_password" \
CAJA_PRINT_URL=http://127.0.0.1:17891 CAJA_PRINT_TOKEN=simulation-token \
java -jar backend/target/suture-caja-0.1.0.jar >/tmp/suture-caja-simulation-backend.log 2>&1 &
api_pid=$!
for _ in {1..40}; do
  if curl -fsS -u "simadmin:$admin_password" "http://127.0.0.1:$api_port/api/me" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$api_pid" 2>/dev/null; then
    tail -40 /tmp/suture-caja-simulation-backend.log >&2
    exit 1
  fi
  sleep 1
done
curl -fsS -u "simadmin:$admin_password" "http://127.0.0.1:$api_port/api/me" >/dev/null
CAJA_SIMULATION_TEST_DB=1 \
CAJA_SIMULATION_EXPECT_PRINT_FAILURE=1 \
CAJA_SIMULATION_URL="http://127.0.0.1:$api_port" \
CAJA_SIMULATION_ADMIN=simadmin \
CAJA_SIMULATION_ADMIN_PASSWORD="$admin_password" \
python3 scripts/mvp-simulation.py
echo 'Pruebas terminadas; la base descartable se elimina al salir.'
