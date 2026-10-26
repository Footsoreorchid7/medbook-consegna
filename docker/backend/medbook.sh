#!/bin/sh
# Avvio nel container: la password del database arriva da un segreto Compose e non dall'ambiente.
# Comandi: server (predefinito), dati (caricamento dei dati dimostrativi), stato-dati.
set -eu

file="${MEDBOOK_DB_PASSWORD_FILE:-}"
if [ -z "$file" ] || [ ! -r "$file" ]; then
	echo "Segreto della password del database non leggibile (MEDBOOK_DB_PASSWORD_FILE)." >&2
	exit 1
fi
MEDBOOK_DB_PASSWORD="$(cat "$file")"
export MEDBOOK_DB_PASSWORD
unset MEDBOOK_DB_PASSWORD_FILE file

classpath="/opt/medbook/classes:/opt/medbook/lib/*"
case "${1:-server}" in
	server)
		exec java -XX:+ExitOnOutOfMemoryError -cp "$classpath" it.medbook.Main ;;
	dati)
		exec java -cp "$classpath:/opt/medbook/demo" it.medbook.demo.DatiDimostrativi ;;
	stato-dati)
		exec java -cp "$classpath:/opt/medbook/demo" it.medbook.demo.DatiDimostrativi stato ;;
	*)
		echo "Comando non riconosciuto: $1 (ammessi: server, dati, stato-dati)." >&2
		exit 2 ;;
esac
