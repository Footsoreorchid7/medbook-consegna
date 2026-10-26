#!/usr/bin/env bash
# Reset esplicito della demo: rimuove container, reti e volumi (database e referti) di questo
# progetto Compose, dopo controlli sulla destinazione e conferma. Non tocca immagini, .env e
# .medbook-demo/, né risorse di altri progetti.
# Uso: scripts/demo/reset.sh --progetto <nome del progetto Compose>
set -euo pipefail

radice="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$radice"

atteso=""
while [ $# -gt 0 ]; do
	case "$1" in
		--progetto) atteso="${2:-}"; shift 2 ;;
		*) echo "Uso: scripts/demo/reset.sh --progetto <nome>" >&2; exit 2 ;;
	esac
done
if [ -z "$atteso" ]; then
	echo "Indicare il progetto da azzerare con --progetto (per la demo predefinita: medbook-demo)." >&2
	exit 2
fi

progetto="$(docker compose config | sed -n '1s/^name: //p')"
if [[ ! "$progetto" =~ ^medbook-demo[a-z0-9-]*$ ]]; then
	echo "Progetto Compose non riconosciuto come demo MedBook («${progetto}»): nessuna modifica." >&2
	exit 1
fi
if [ "$progetto" != "$atteso" ]; then
	echo "La destinazione indicata («${atteso}») non coincide con il progetto configurato («${progetto}»): nessuna modifica." >&2
	exit 1
fi

volumi="$(docker volume ls -q --filter "label=com.docker.compose.project=$progetto")"
for volume in $volumi; do
	case "$volume" in
		"${progetto}_mysql-dati" | "${progetto}_referti") ;;
		*) echo "Volume inatteso nel progetto: $volume. Nessuna modifica." >&2; exit 1 ;;
	esac
done

echo "Progetto: $progetto"
echo "Container:"; docker compose ps -a --format '  {{.Name}} ({{.State}})'
echo "Volumi da eliminare definitivamente:"; for volume in $volumi; do echo "  $volume"; done
[ -n "$volumi" ] || echo "  (nessuno)"
printf 'Database e referti della demo andranno persi. Digitare il nome del progetto per confermare: '
read -r conferma || conferma=""
if [ "$conferma" != "$progetto" ]; then
	echo "Conferma non corrispondente: nessuna modifica." >&2
	exit 1
fi

docker compose --profile dati down --volumes --remove-orphans
residui="$(docker volume ls -q --filter "label=com.docker.compose.project=$progetto")"
if [ -n "$residui" ]; then
	echo "Volumi ancora presenti: $residui" >&2
	exit 1
fi
echo "Reset completato. Per ricreare la demo:"
echo "  docker compose up -d --wait"
echo "  docker compose run --rm dati"
