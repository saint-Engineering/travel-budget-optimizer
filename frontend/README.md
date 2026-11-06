# Travel Budget Optimizer UI

Frontend standalone Angular per il POC di ottimizzazione del budget viaggi.

## Requisiti

- Node.js compatibile con Angular 21
- npm
- Backend Spring Boot disponibile su `http://localhost:8080`

## Avvio in sviluppo

Dalla cartella `frontend`, installare le dipendenze con `npm install` e avviare con `npm start`. Il dev server inoltra `/api/**` a `http://localhost:8080` tramite `proxy.conf.json`.

Il bootstrap Angular configura `provideHttpClient()` in `src/app/app.config.ts`, necessario al `TravelService`.

## Build

Eseguire `npm run build` dalla cartella `frontend`.
