#!/usr/bin/env bash
# MySQL 8.4 temporaneo dedicato ai test end-to-end; non riusa database applicativi.
# crea --schema nuovo|migrato --porta <porta diversa da 3306> --stato <directory nuova esterna>
# verifica --stato <directory> controlla identità, UUID, cataloghi e privilegi.
# elimina --stato <directory> rimuove soltanto container e volume riconosciuti.
# Lo schema migrato usa la fixture SQL inclusa, senza dipendere dalla storia Git.
# Credenziali casuali in file privati; mai stampate o passate negli argomenti dei processi.
set -euo pipefail
umask 077

IMMAGINE="mysql:8.4"
ETICHETTA="it.medbook.e2e.proprietario"
LAUNCHER="e2elauncher"
RUNTIME="e2eruntime"
REPO="$(cd "$(dirname "$0")/../.." && pwd -P)"

errore() { echo "ERRORE: $*" >&2; exit 1; }
info() { echo "[mysql-e2e] $*"; }

azione="${1:-}"; [ -n "$azione" ] && shift || true
SCHEMA=""; PORTA=""; STATO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --schema) SCHEMA="$2"; shift 2 ;;
    --porta) PORTA="$2"; shift 2 ;;
    --stato) STATO="$2"; shift 2 ;;
    *) errore "argomento sconosciuto: $1" ;;
  esac
done
[ -n "$STATO" ] || errore "--stato obbligatorio"

fuori_dal_repository() {
  local assoluto
  assoluto="$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")"
  case "$assoluto/" in "$REPO"/*) errore "--stato deve stare fuori dal repository ($REPO)" ;; esac
}

# Password soltanto nell’ambiente docker exec, con nome della variabile senza valore negli argomenti.
root_sql() { MYSQL_PWD="$ROOT_PASSWORD" docker exec -i -e MYSQL_PWD "$CONTAINER_ID" mysql --protocol=socket -uroot -N "$@"; }
come() { # Parametri: utente, password, catalogo, SQL.
  MYSQL_PWD="$2" docker exec -i -e MYSQL_PWD "$CONTAINER_ID" mysql --protocol=tcp -h127.0.0.1 -u"$1" -N -D "$3" -e "$4"
}
negato() { # Deve fallire per accesso negato (1044/1142/1227), non per sintassi SQL.
  local out
  if out="$(come "$@" 2>&1)"; then errore "operazione consentita a $1 ma attesa negata: $4"; fi
  echo "$out" | grep -Eq 'ERROR (1044|1142|1227)' || errore "errore inatteso per $1 su '$4': $out"
}

carica_stato() {
  [ -f "$STATO/istanza.env" ] || errore "stato non trovato: $STATO/istanza.env"
  # shellcheck disable=SC1091
  . "$STATO/istanza.env"
  [ -f "$STATO/root.env" ] && . "$STATO/root.env" || true
  [ -f "$STATO/e2e.env" ] && . "$STATO/e2e.env" || true
}

verifica_container() {
  local id nome immagine etichetta volume binding
  id="$(docker inspect -f '{{.Id}}' "$CONTAINER_ID" 2>/dev/null)" || errore "container $CONTAINER_ID non trovato"
  [ "$id" = "$CONTAINER_ID" ] || errore "identità del container diversa"
  nome="$(docker inspect -f '{{.Name}}' "$CONTAINER_ID")"; [ "$nome" = "/$CONTAINER_NOME" ] || errore "nome del container diverso"
  immagine="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER_ID")"; [ "$immagine" = "$IMMAGINE" ] || errore "immagine inattesa: $immagine"
  etichetta="$(docker inspect -f "{{index .Config.Labels \"$ETICHETTA\"}}" "$CONTAINER_ID")"
  [ "$etichetta" = "$PROPRIETARIO" ] || errore "etichetta di proprietà del container assente o diversa"
  volume="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' "$CONTAINER_ID")"
  [ "$volume" = "$VOLUME" ] || errore "volume dei dati diverso da quello creato"
  binding="$(docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}} {{end}}{{end}}' "$CONTAINER_ID")"
  [ "$binding" = "3306/tcp=127.0.0.1:$PORTA " ] || errore "porte pubblicate inattese: $binding"
  [ "$(docker volume inspect -f "{{index .Labels \"$ETICHETTA\"}}" "$VOLUME")" = "$PROPRIETARIO" ] \
    || errore "etichetta di proprietà del volume assente o diversa"
  info "container ${CONTAINER_ID:0:12} ($CONTAINER_NOME), immagine $immagine, volume $VOLUME, 127.0.0.1:$PORTA"
}

verifica_mysql() {
  local versione uuid host attesi presenti
  versione="$(root_sql -e 'SELECT @@version')"; case "$versione" in 8.4.*) ;; *) errore "MySQL $versione, richiesto 8.4" ;; esac
  uuid="$(root_sql -e 'SELECT @@server_uuid')"; [ "$uuid" = "$SERVER_UUID" ] || errore "server UUID diverso"
  host="$(root_sql -e 'SELECT @@hostname')"; [ "$host" = "${CONTAINER_ID:0:12}" ] || errore "hostname MySQL diverso dal container"
  attesi="$(printf '%s\n' information_schema mysql performance_schema sys "$TEMPLATE" | sort)"
  presenti="$(root_sql -e "SHOW DATABASES" | grep -v '^medbook_test_e2e_r' | sort)"
  [ "$presenti" = "$attesi" ] || errore "cataloghi inattesi sull'istanza: $(echo "$presenti" | tr '\n' ' ')"
  [ "$(root_sql -e "SELECT COUNT(*) FROM mysql.user WHERE user = 'root' AND host <> 'localhost'")" = "0" ] \
    || errore "root raggiungibile via rete"
  info "MySQL $versione, server_uuid $uuid, hostname = container, cataloghi: $(echo "$presenti" | tr '\n' ' ')"
}

grant_normalizzati() { # Account in righe ordinate di oggetto e privilegi.
  root_sql -r -e "SHOW GRANTS FOR '$1'@'%'" | sed -E 's/^GRANT (.*) ON ([^ ]+) TO .*/\2: \1/' \
    | while IFS=': ' read -r oggetto privilegi; do
        echo "$oggetto: $(echo "$privilegi" | tr ',' '\n' | sed 's/^ *//' | sort | paste -sd, -)"
      done | sort
}

verifica_privilegi() {
  local run='`medbook\_test\_e2e\_r%`.*' atteso sonda
  atteso="$(printf '%s\n' "*.*: USAGE" "$run: ALTER,CREATE,DROP,INDEX,INSERT,REFERENCES,SELECT" "\`$TEMPLATE\`.*: SELECT" | sort)"
  [ "$(grant_normalizzati "$LAUNCHER")" = "$atteso" ] || errore "grant del launcher diversi dal minimo: $(grant_normalizzati "$LAUNCHER" | tr '\n' ';')"
  atteso="$(printf '%s\n' "*.*: USAGE" "$run: DELETE,INSERT,SELECT,UPDATE" | sort)"
  [ "$(grant_normalizzati "$RUNTIME")" = "$atteso" ] || errore "grant del runtime diversi dal minimo: $(grant_normalizzati "$RUNTIME" | tr '\n' ';')"
  [ "$(root_sql -e "SELECT COUNT(*) FROM mysql.role_edges WHERE to_user IN ('$LAUNCHER','$RUNTIME')")" = "0" ] || errore "ruoli assegnati agli account E2E"

  # Privilegi effettivi su catalogo sonda creato e rimosso dallo stesso launcher.
  sonda="medbook_test_e2e_r00000000000000_$(openssl rand -hex 8)"
  local L="$MEDBOOK_E2E_LAUNCHER_PASSWORD" R="$MEDBOOK_E2E_PASSWORD"
  come "$LAUNCHER" "$L" "$TEMPLATE" "CREATE DATABASE \`$sonda\`"
  come "$LAUNCHER" "$L" "$sonda" "CREATE TABLE e2e_proprietario (token CHAR(32) NOT NULL PRIMARY KEY, creato_il DATETIME NOT NULL) ENGINE=InnoDB SELECT REPEAT('a', 32) AS token, UTC_TIMESTAMP() AS creato_il"
  [ "$(come "$LAUNCHER" "$L" "$sonda" "SELECT COUNT(*) FROM e2e_proprietario")" = "1" ] || errore "marker non leggibile dal launcher"
  come "$LAUNCHER" "$L" "$sonda" "INSERT INTO e2e_proprietario VALUES (REPEAT('b', 32), UTC_TIMESTAMP())"
  negato "$LAUNCHER" "$L" "$sonda" "UPDATE e2e_proprietario SET token = REPEAT('c', 32)"
  negato "$LAUNCHER" "$L" "$sonda" "DELETE FROM e2e_proprietario"
  negato "$LAUNCHER" "$L" "$TEMPLATE" "CREATE DATABASE \`medbook_e2e_non_consentito\`"
  negato "$LAUNCHER" "$L" "$TEMPLATE" "DROP DATABASE \`$TEMPLATE\`"
  negato "$LAUNCHER" "$L" "$TEMPLATE" "CREATE TABLE e2e_non_consentita (id INT)"
  negato "$LAUNCHER" "$L" "$TEMPLATE" "DELETE FROM utente"
  negato "$LAUNCHER" "$L" "$TEMPLATE" "SELECT COUNT(*) FROM mysql.user"
  come "$RUNTIME" "$R" "$sonda" "SELECT COUNT(*) FROM e2e_proprietario" >/dev/null
  negato "$RUNTIME" "$R" "$sonda" "CREATE TABLE e2e_non_consentita (id INT)"
  negato "$RUNTIME" "$R" "$sonda" "DROP TABLE e2e_proprietario"
  negato "$RUNTIME" "$R" "$sonda" "CREATE DATABASE \`medbook_test_e2e_r00000000000000_0000000000000001\`"
  negato "$RUNTIME" "$R" "$sonda" "DROP DATABASE \`$sonda\`"
  negato "$RUNTIME" "$R" "$sonda" "SELECT 1 FROM \`$TEMPLATE\`.utente"
  negato "$RUNTIME" "$R" "$sonda" "SELECT COUNT(*) FROM mysql.user"
  come "$LAUNCHER" "$L" "$TEMPLATE" "DROP DATABASE \`$sonda\`"
  info "grant esatti e privilegi effettivi verificati (launcher: DDL + SELECT/INSERT su run, SELECT sul modello; runtime: DML su run)"
}

schema_sql() {
  local file="$REPO/database/schema.sql"
  if [ "$SCHEMA" = "migrato" ]; then file="$REPO/src/test/resources/database/schema-pre003.sql"; fi
  # Importa esclusivamente le tabelle nel catalogo temporaneo verificato.
  awk 'f || /^CREATE TABLE/ { f = 1 } f' "$file"
  if [ "$SCHEMA" = "migrato" ]; then cat "$REPO/database/migrations/003_utente_accesso_modifica.sql"; fi
}

crea() {
  [ "$SCHEMA" = "nuovo" ] || [ "$SCHEMA" = "migrato" ] || errore "--schema nuovo|migrato"
  [[ "$PORTA" =~ ^[0-9]{4,5}$ ]] && [ "$PORTA" -ge 1024 ] && [ "$PORTA" -le 65535 ] && [ "$PORTA" -ne 3306 ] || errore "porta non ammessa"
  [ ! -e "$STATO" ] || errore "$STATO esiste già: usare una directory nuova"
  fuori_dal_repository "$STATO"
  if nc -z 127.0.0.1 "$PORTA" 2>/dev/null; then errore "porta $PORTA già in uso"; fi
  local sql; sql="$(schema_sql)"
  ! echo "$sql" | grep -Eiq '^[[:space:]]*(USE[[:space:]]|CREATE[[:space:]]+(DATABASE|SCHEMA)|DROP[[:space:]]+(DATABASE|SCHEMA))' \
    || errore "lo schema contiene istruzioni a livello di catalogo"
  if [ "$SCHEMA" = "migrato" ]; then
    ! grep -q stato_accesso "$REPO/src/test/resources/database/schema-pre003.sql" || errore "fixture precedente già migrata"
  fi

  mkdir -m 700 "$STATO"
  PROPRIETARIO="$(openssl rand -hex 16)"
  local suffisso; suffisso="$(openssl rand -hex 6)"
  CONTAINER_NOME="medbook-e2e-$SCHEMA-$suffisso"; VOLUME="medbook-e2e-$SCHEMA-$suffisso"
  TEMPLATE="medbook_test_e2e_template_$SCHEMA"
  ROOT_PASSWORD="$(openssl rand -hex 24)"
  MEDBOOK_E2E_LAUNCHER_PASSWORD="$(openssl rand -hex 24)"; MEDBOOK_E2E_PASSWORD="$(openssl rand -hex 24)"
  printf 'ROOT_PASSWORD=%s\n' "$ROOT_PASSWORD" > "$STATO/root.env"

  ! docker volume inspect "$VOLUME" >/dev/null 2>&1 || errore "volume $VOLUME già esistente"
  docker volume create --label "$ETICHETTA=$PROPRIETARIO" "$VOLUME" >/dev/null
  CONTAINER_ID="$(MYSQL_ROOT_PASSWORD="$ROOT_PASSWORD" docker run -d --name "$CONTAINER_NOME" \
      --label "$ETICHETTA=$PROPRIETARIO" -e MYSQL_ROOT_PASSWORD -e MYSQL_ROOT_HOST=localhost \
      -v "$VOLUME:/var/lib/mysql" -p "127.0.0.1:$PORTA:3306" "$IMMAGINE")"
  cat > "$STATO/istanza.env" <<EOF
CONTAINER_ID=$CONTAINER_ID
CONTAINER_NOME=$CONTAINER_NOME
VOLUME=$VOLUME
PROPRIETARIO=$PROPRIETARIO
PORTA=$PORTA
SCHEMA=$SCHEMA
TEMPLATE=$TEMPLATE
EOF
  info "attesa dell'avvio di MySQL (l'entrypoint riavvia il server una volta dopo l'inizializzazione)"
  local i
  for i in $(seq 1 120); do
    if docker logs "$CONTAINER_ID" 2>&1 | grep -q 'ready for connections.*port: 3306' \
       && root_sql -e 'SELECT 1' >/dev/null 2>&1; then break; fi
    [ "$i" -lt 120 ] || errore "MySQL non disponibile"; sleep 2
  done
  SERVER_UUID="$(root_sql -e 'SELECT @@server_uuid')"
  echo "SERVER_UUID=$SERVER_UUID" >> "$STATO/istanza.env"
  verifica_container

  root_sql -e "CREATE DATABASE \`$TEMPLATE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
  echo "$sql" | root_sql -D "$TEMPLATE"
  root_sql <<EOF
CREATE USER '$LAUNCHER'@'%' IDENTIFIED BY '$MEDBOOK_E2E_LAUNCHER_PASSWORD';
CREATE USER '$RUNTIME'@'%' IDENTIFIED BY '$MEDBOOK_E2E_PASSWORD';
GRANT SELECT, INSERT, CREATE, DROP, ALTER, INDEX, REFERENCES ON \`medbook\\_test\\_e2e\\_r%\`.* TO '$LAUNCHER'@'%';
GRANT SELECT ON \`$TEMPLATE\`.* TO '$LAUNCHER'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`medbook\\_test\\_e2e\\_r%\`.* TO '$RUNTIME'@'%';
EOF
  cat > "$STATO/e2e.env" <<EOF
export MEDBOOK_E2E_TEMPLATE_URL=jdbc:mysql://127.0.0.1:$PORTA/$TEMPLATE
export MEDBOOK_E2E_SERVER_UUID=$SERVER_UUID
export MEDBOOK_E2E_LAUNCHER_USERNAME=$LAUNCHER
export MEDBOOK_E2E_LAUNCHER_PASSWORD=$MEDBOOK_E2E_LAUNCHER_PASSWORD
export MEDBOOK_E2E_USERNAME=$RUNTIME
export MEDBOOK_E2E_PASSWORD=$MEDBOOK_E2E_PASSWORD
EOF
  info "$TEMPLATE: $(root_sql -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$TEMPLATE'") tabelle, \
stato_accesso=$(root_sql -e "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '$TEMPLATE' AND TABLE_NAME = 'utente' AND COLUMN_NAME = 'stato_accesso'"), \
routine=$(root_sql -e "SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = '$TEMPLATE'")"
  verifica
}

verifica() {
  carica_stato
  verifica_container
  verifica_mysql
  verifica_privilegi
  info "istanza verificata; per i test: source $STATO/e2e.env"
}

elimina() {
  carica_stato
  verifica_container   # Rifiuta identità, immagine, etichette, volume o binding discordanti.
  docker rm -f "$CONTAINER_ID" >/dev/null
  [ "$(docker volume inspect -f "{{index .Labels \"$ETICHETTA\"}}" "$VOLUME")" = "$PROPRIETARIO" ] || errore "volume non verificato, non rimosso"
  docker volume rm "$VOLUME" >/dev/null
  rm -f "${STATO:?}/root.env" "${STATO:?}/e2e.env" "${STATO:?}/istanza.env"
  rmdir "${STATO:?}"
  info "container ${CONTAINER_ID:0:12}, volume $VOLUME e stato rimossi"
}

case "$azione" in
  crea) crea ;;
  verifica) verifica ;;
  elimina) elimina ;;
  *) errore "uso: $0 crea|verifica|elimina --stato <dir> [--schema nuovo|migrato --porta <n>]" ;;
esac
