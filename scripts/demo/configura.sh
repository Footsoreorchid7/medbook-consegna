#!/usr/bin/env bash
# Genera la configurazione locale della demo Docker: .env (letto da Compose) e la cartella privata
# .medbook-demo/ con password casuali e credenziali dimostrative. Non sovrascrive file esistenti.
# Uso: scripts/demo/configura.sh [--porta <1024-65535>]
set -euo pipefail
umask 077

radice="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$radice"

porta=8088
while [ $# -gt 0 ]; do
	case "$1" in
		--porta) porta="${2:-}"; shift 2 ;;
		*) echo "Uso: scripts/demo/configura.sh [--porta <1024-65535>]" >&2; exit 2 ;;
	esac
done
if [[ ! "$porta" =~ ^[0-9]{4,5}$ ]] || [ "$porta" -lt 1024 ] || [ "$porta" -gt 65535 ]; then
	echo "Porta non valida: indicare un numero tra 1024 e 65535." >&2
	exit 2
fi
for percorso in .env .medbook-demo; do
	if [ -e "$percorso" ] || [ -L "$percorso" ]; then
		echo "$percorso esiste già: nessuna modifica. Per rigenerare, spostarlo o rimuoverlo esplicitamente." >&2
		exit 1
	fi
done

casuale() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }

# La cartella resta privata; i file sono leggibili dagli utenti dei container che li montano come segreti.
mkdir -m 700 .medbook-demo
for nome in mysql-root mysql-app mysql-dati; do
	(set -C; printf '%s' "$(casuale 24)" > ".medbook-demo/$nome")
done
{
	echo "# Credenziali dimostrative della demo locale MedBook: account fittizi, non riutilizzare altrove."
	echo "# ruolo email password"
	while read -r ruolo email; do
		printf '%s %s Demo-%s\n' "$ruolo" "$email" "$(casuale 8)"
	done <<-ELENCO
		amministratore amministrazione@demo.medbook.example
		desk segreteria@demo.medbook.example
		cardiologia marta.galli@demo.medbook.example
		dermatologia luca.moretti@demo.medbook.example
		medicina-generale elena.conti@demo.medbook.example
		paziente giulia.neri@demo.medbook.example
		paziente2 paolo.rinaldi@demo.medbook.example
		medico-paziente sara.fontana@demo.medbook.example
	ELENCO
} > .medbook-demo/credenziali-demo.txt
chmod 644 .medbook-demo/mysql-root .medbook-demo/mysql-app .medbook-demo/mysql-dati .medbook-demo/credenziali-demo.txt

(set -C; cat > .env) <<EOF
# Configurazione locale della demo Docker, generata da scripts/demo/configura.sh. Non versionare.
MEDBOOK_WEB_PORT=$porta
MEDBOOK_TZ=Europe/Rome
MEDBOOK_DEMO_INSTALLAZIONE=$(casuale 16)
MEDBOOK_SIMULATED_PAYMENT_OUTCOME=PAGATO
EOF

echo "Configurazione creata: .env e .medbook-demo/ (password non mostrate)."
echo "Indirizzo della demo: http://localhost:$porta"
echo "Credenziali dimostrative: cat .medbook-demo/credenziali-demo.txt"
