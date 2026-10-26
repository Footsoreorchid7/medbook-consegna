#!/bin/bash
# Pronto solo se il backend risponde e legge il database: il catalogo pubblico richiede entrambi.
exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
printf 'GET /api/prestazioni HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r -t 5 stato <&3 || exit 1
[[ "$stato" == HTTP/1.?" 200 "* ]]
