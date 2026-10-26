# Frontend MedBook

Vue 3, Vue Router, JavaScript e Vite. Richiede Node `^22.12.0 || ^24.0.0 || >=26.0.0`.
Le versioni delle dipendenze sono fissate da `package-lock.json`.

Dalla directory `frontend`:

```sh
npm ci
npm run dev
npm test
npm run build
```

Il server di sviluppo usa `http://localhost:5173`, porta fissa (`strictPort`). Il proxy `/api`
inoltra a `http://127.0.0.1:8080`, preservando Origin e cookie. Il backend deve ammettere
`MEDBOOK_AUTH_ORIGIN=http://localhost:5173` e usare `MEDBOOK_AUTH_MODE=local-http`.
I test Vitest utilizzano jsdom e risposte simulate: non dimostrano l'integrazione reale
con cookie, proxy, database o qpdf. Non usare il database applicativo come fixture.

La build produce `dist/`, esclusa da Git. `npm run preview` visualizza la build, ma non
ha il proxy di sviluppo: da solo non avvia l'applicazione completa. Un server per la build
deve servire le rotte Vue con fallback a `index.html` e inoltrare `/api` al backend.
Non sono inclusi una configurazione di pubblicazione o servizi Docker.

Catalogo e disponibilità sono pubblici. Le pagine riservate dipendono dalle capacità
restituite dalla sessione; il server verifica sempre i permessi. Cookie HttpOnly e token
CSRF gestiscono l'autenticazione; nessuna password o bozza clinica va salvata in storage
persistente del browser. I pagamenti online sono simulati.

Per preparare backend, database e storage seguire il [README principale](../README.md).
