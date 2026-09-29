#!/usr/bin/env bash
# Lance uniquement les processus de ce projet. Données conservées.
set -eu
cd "$(dirname "$0")"
mkdir -p .run
for tool in docker java mvn node npm curl ss setsid flock; do
  command -v "$tool" >/dev/null || { echo "Outil absent : $tool"; exit 1; }
done
exec 9>.run/launcher.lock
flock -n 9 || { echo "Ce projet est déjà lancé par demarrer.sh."; exit 1; }
for port in 3000 8080; do
  if ss -H -ltn "sport = :$port" | grep -q .; then
    echo "Port $port occupé. Arrêtez son application ou l'instance précédente avant de relancer."
    exit 1
  fi
done
cleanup() { bash scripts/stop-app.sh; }
trap cleanup EXIT
trap 'exit 0' INT TERM

docker compose up -d > docker.log 2>&1 || { cat docker.log; exit 1; }
ready=0
for ((i=0;i<30;i++)); do
  if docker exec ebcdc-db pg_isready -U ebcdc >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "Base indisponible : docker logs ebcdc-db"; exit 1; }
if ! curl --max-time 5 -fsS http://localhost:11434/api/tags >/dev/null; then
  echo "Ollama indisponible : le workflow fonctionne, mais les analyses IA échoueront."
fi

start_process() {
  local name="$1" folder="$2"; shift 2
  (cd "$folder"; exec setsid "$@") 9>&- > "$name.log" 2>&1 &
  local pid=$!
  # Le PID et son instant de création permettent d'éviter d'arrêter un PID réutilisé.
  local started
  started=$(sed 's/^.*) //' "/proc/$pid/stat" | awk '{print $20}')
  echo "$pid $started" > ".run/$name.pid"
}
start_process backend backend mvn -B spring-boot:run
ready=0
for ((i=0;i<90;i++)); do
  code=$(curl --max-time 2 -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/providers || true)
  if [ "$code" = 401 ]; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { tail -40 backend.log; exit 1; }
# Les données de démonstration sont un choix explicite, indépendant du démarrage.
if [ "${EBCDC_SEED_DEMO:-0}" = 1 ]; then
  docker exec -i ebcdc-db psql -v ON_ERROR_STOP=1 -U ebcdc -d ebcdc < scripts/seed-demo.sql
fi
if [ ! -d frontend/node_modules ]; then
  (cd frontend; npm ci) > frontend-install.log 2>&1 || { tail -30 frontend-install.log; exit 1; }
fi
start_process frontend frontend npm run dev
ready=0
for ((i=0;i<45;i++)); do
  if curl --max-time 3 -fsS http://localhost:3000 >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { tail -40 frontend.log; exit 1; }
echo "Application prête : http://localhost:3000"
echo "Comptes : metier@demo.fr / projet@demo.fr / prestataire@demo.fr ; mot de passe : demo1234"
echo "Logs : backend.log, frontend.log. Arrêt : Ctrl+C ou ./arreter.sh"
wait
