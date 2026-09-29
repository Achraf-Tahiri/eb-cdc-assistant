#!/usr/bin/env bash
# Arrête seulement les groupes de processus enregistrés par demarrer.sh.
set -u
cd "$(dirname "$0")/.."
for name in frontend backend; do
  file=".run/$name.pid"
  [ -f "$file" ] || continue
  read -r pid started < "$file"
  if [[ "$pid" =~ ^[0-9]+$ ]] && [ -r "/proc/$pid/stat" ]; then
    actual=$(sed 's/^.*) //' "/proc/$pid/stat" | awk '{print $20}')
    if [ "$actual" = "$started" ]; then
      kill -TERM -- "-$pid" 2>/dev/null || true
      echo "$name : arrêt demandé."
    fi
  fi
  rm -f "$file"
done
