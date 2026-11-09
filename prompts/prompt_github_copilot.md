# Prompt per GitHub Copilot: Implementazione POC Ottimizzazione Budget Viaggi

Questo documento raccoglie la sequenza ordinata di prompt strutturati da fornire a **GitHub Copilot** (utilizzando la chat di sistema o i commenti inline) per generare l'intero stack del progetto, rispettando i vincoli di **Java 25**, **Spring Boot 3.4+**, **Spring AI / LangChain4j** ed **Angular**.

---

## 🛠️ FASE 1 & 2: Inizializzazione e Configurazione Core Backend

### Prompt 1: Configurazione del file `pom.xml` (Java 25 & Spring Boot 3.4)
```text
Genera un file pom.xml completo per un progetto Spring Boot versione 3.4.0 (o successiva stabile) che utilizzi Java 25 come versione del compilatore. Il build tool deve essere Apache Maven.
Includi le seguenti dipendenze e configurazioni senza omettere nulla:
1. spring-boot-starter-web (per i servizi REST e SSE)
2. spring-boot-starter-security (per lo strato di autenticazione e filtri CORS/CSRF)
3. Dipendenze per Spring AI (in particolare lo starter per il Server MCP con trasporto HTTP/SSE: spring-ai-mcp-server-spring-boot-starter)
4. Dipendenze per LangChain4j core e la gestione dei Tool (@Tool / Function Calling)
5. Gestione delle versioni tramite un blocco <dependencyManagement> appropriato per Spring AI e LangChain4j coerente con lo scenario 2026.
Abilita i corretti tag di compilazione per Java 25.
```

### Prompt 2: File di Configurazione `application.yml`
```text
Crea il file src/main/resources/application.yml per l'applicazione Spring Boot. La configurazione deve:
1. Impostare la porta del server su 8080.
2. Abilitare e configurare il server MCP nativo di Spring AI impostando il trasporto su 'sse'.
3. Definire esplicitamente i path per il protocollo MCP: '/mcp/sse' per la connessione SSE e '/mcp/message' per i messaggi di tipo POST.
4. Inserire i metadati del server MCP (name: "Travel-Budget-Optimizer-Server", version: "1.0.0").
5. Configurare una sezione fittizia per le credenziali dei Cloud Tool esterni (Brave Search, Google Maps, Browserless, Neon Postgres) recuperandole tramite variabili d'ambiente con valori di fallback (es. ${BRAVE_API_KEY:fake-key}).
6. Configurare Spring Security per permettere l'accesso pubblico agli endpoint /api/viaggi/** e /mcp/** per scopi di test nel POC.
```

---

## 🏗️ FASE 2: Cloud Tool Clients (@Tool)

### Prompt 3: Interfacce e DTO di Business per le alternative di viaggio
```text
Crea i Record Java 25 o DTO immutabili per strutturare i dati del viaggio:
1. TravelOption: rappresenta una singola alternativa di viaggio (es. mezzo, costo totalizzato, tempo di percorrenza, descrizione dettagliata).
2. TravelOptionsResponse: contiene una lista di 3 oggetti TravelOption ottimizzati rispetto al budget stabilito dall'utente.
Assicurati che le classi siano facilmente serializzabili in JSON da Jackson.
```

### Prompt 4: Sviluppo del client HTTP Cloud Tools con LangChain4j `@Tool`
```text
Sviluppa una classe Java intitolata `CloudTravelTools` annotata come @Component. Questa classe deve fungere da contenitore per i Tool che l'LLM invocherà tramite Function Calling di LangChain4j. Implementa tre metodi con annotazione @Tool contenenti descrizioni testuali chiarissime per il modello:
1. calcolaItinerario(String partenza, String destinazione): effettua una chiamata HTTP REST verso un'API cloud di mappe (es. Google Maps/GraphHopper) per ricavare distanza e tempi. Simula la chiamata con un RestClient di Spring Boot 3.4+.
2. cercaTariffeTrasporti(String partenza, String destinazione): effettua chiamate HTTP verso Brave Search / Perplexity API ed estrae un testo formattato contenente i prezzi reali di treni, voli e bus.
3. cercaCostoAlloggi(String destinazione): delega lo scraping a un servizio remoto tipo Browserless.io via HTTP e restituisce le tariffe medie per notte.
Usa il nuovo RestClient di Spring Boot per tutte le chiamate di rete esterne.
```

---

## ⚡ FASE 3: Workflow Asincrono e Servizi REST (Engine Core)

### Prompt 5: Implementazione del motore asincrono `TravelOptimizationEngine`
```text
Crea il servizio Java `TravelOptimizationEngine`. Questo servizio deve coordinare l'elaborazione pesante (15-30 secondi).
1. Implementa un metodo asincrono 'avviaOttimizzazione(String jobId, String partenza, String destinazione, double budget)' annotato con @Async.
2. All'interno del metodo, simula l'orchestrazione delle 3 Fasi inviando aggiornamenti di stato a un gestore centralizzato (es. Fase 1: Mappatura itinerario avviata..., Fase 2: Analisi costi completata, Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres tramite JDBC).
3. Salva lo stato di avanzamento e, alla fine, il payload JSON finale con le 3 alternative all'interno di una ConcurrentHashMap concorrente o simulando la scrittura su DB Cloud, in modo che sia accessibile tramite il Job ID.
```

### Prompt 6: Controller REST e Endpoint SSE per Angular
```text
Crea un `TravelRestController` in Spring Boot che espone due endpoint per l'applicazione Angular:
1. POST '/api/viaggi/ottimizza': riceve un JSON con partenza, destinazione e budgetMassimo. Genera un UUID univoco (Job ID), avvia in background il TravelOptimizationEngine passando il Job ID, e restituisce immediatamente un HTTP 202 Accepted contenente il Job ID in una mappa.
2. GET '/api/viaggi/status/{jobId}': restituisce un SseEmitter. Si aggancia al flusso del TravelOptimizationEngine per inviare in tempo reale (streaming SSE) gli eventi di testo delle varie Fasi alla Web App Angular. Quando il motore termina, invia l'oggetto JSON finale con le 3 alternative e chiude l'SseEmitter in modo pulito con emitter.complete().
Gestisci correttamente i potenziali errori e i timeout dell'SseEmitter.
```

---

## 🔌 FASE 4: Esposizione del Server MCP (Bridge Asincrono)

### Prompt 7: Implementazione del Bridge MCP su Spring AI
```text
Crea la classe Spring `TravelMcpServerTools` per esporre le funzionalità del POC come tool del server MCP fornito da Spring AI.

Vincoli di ambito e architettura:
- Limita le modifiche a `TravelMcpServerTools` e ai relativi test.
- Riutilizza `TravelOptimizationEngine`, `TravelOptionsResponse`, gli altri DTO e le API esistenti per stato e risultato. Non modificare l'engine, i DTO, il trasporto MCP, la configurazione del server o la persistenza; non aggiungere dipendenze.
- L'applicazione può usare LangChain4j per invocare server MCP di terze parti, ma questa classe implementa esclusivamente il ruolo di fornitore MCP del POC con Spring AI. Non implementare qui un client MCP LangChain4j.
- Registra i tool usando l'annotazione Spring AI `@McpTool` e gli schemi/annotazioni di parametro previsti dalla versione Spring AI già presente nel progetto. I tool devono essere esposti dal server MCP già configurato.

Implementa due tool MCP:
1. `ottimizzaBudgetViaggio(partenza, destinazione, budgetMassimo)`: valida gli input, genera un UUID univoco e avvia l'ottimizzazione tramite `TravelOptimizationEngine`. Restituisce subito un contenuto strutturato con il job ID e lo stato iniziale; non attendere il completamento e non usare polling, cicli di attesa o blocchi del thread.
2. `recuperaRisultatoOttimizzazione(jobId)`: usa le API di stato e risultato già disponibili, senza polling. Se il job è ancora in corso, restituisce un contenuto strutturato con job ID e stato; se lo stato è `COMPLETED`, restituisce le tre alternative come contenuto strutturato conforme ai campi di `TravelOptionsResponse`. Non serializzare il DTO come testo JSON.

Gestione degli errori:
- Un input non valido deve produrre un errore MCP riconoscibile come input non valido.
- Gli errori dell'engine devono produrre un errore MCP interno; registra nel log i dettagli diagnostici accurati senza esporre dettagli interni non necessari al client.
- Gestisci timeout massimo di 120 secondi, interruzione e completamento senza payload con errori MCP specifici. I timeout devono essere ripetibili su richiesta del client/utente, senza avviare automaticamente un nuovo job.
- Gestisci in modo esplicito job ID sconosciuti e gli stati restituiti dalle API esistenti; non inventare stati o convertire errori in risposte di successo.

Aggiungi test mirati per registrazione e schema dei tool, validazione degli input, avvio con restituzione immediata del job ID, recupero degli stati non terminali e del risultato `COMPLETED`, nonché per job sconosciuti, timeout, interruzione, payload assente ed errori dell'engine. Segui le convenzioni di test e i pattern già presenti nel progetto.
```

---

## 💻 FASE 5: Sviluppo Frontend (Angular Web App)

### Prompt 8: Servizio Angular per la comunicazione REST e lo Stream SSE
```text
Genera, un applicazione Angular e in questa un servizio `TravelService` per comunicare con il backend Spring Boot e la relativa interfaccia utente.
 
Non creare o modificare la configurazione di bootstrap: considera `provideHttpClient()` gi  configurato nell applicazione.
 
Se il servizio del backend nel backend non è disponibile fermati.
 
Il servizio TravelService deve esporre:
1. `avviaRichiestaOttimizzazione(partenza: string, destinazione: string, budgetMassimo: number): Observable<{ jobId: string }>`
  - Esegue una POST a `/api/viaggi/ottimizza`.
  - Invia un corpo JSON con le propriet  `partenza`, `destinazione` e `budgetMassimo`.
  - Tipizza la risposta in base al contratto `{ jobId: string }`.
2. `ascoltaAggiornamentiStatus(jobId: string): Observable<TravelSseEvent>`
  - Apre un `EventSource` verso `/api/viaggi/status/{jobId}`.
  - Gestisce gli eventi SSE nominati dal backend:
    - `status`: emette l aggiornamento testuale;
    - `result`: emette il risultato JSON;
    - `timeout`: emette il messaggio di timeout.
  - Definisce un tipo discriminato `TravelSseEvent` per distinguere chiaramente questi tre eventi e i rispettivi payload.
  - Al ricevimento di `result` o `timeout`, chiude l `EventSource` e completa l Observable.
  - Chiude l `EventSource` anche quando il consumatore si disiscrive, evitando connessioni pendenti.
  - Gestisce gli errori di connessione senza lasciare risorse aperte; chiarisce nel codice se lasciare a `EventSource` il tentativo automatico di riconnessione per errori transitori.
Usa `HttpClient` per la POST e l API nativa `EventSource` per SSE. Non aggiungere componenti UI n  modificare il backend.
ha il menu contestuale

```

### Prompt 9: Componente Angular Form e Visualizzazione Card Risultati
```text
Genera un componente Angular completo (HTML standalone, TypeScript e CSS integrati nello stesso file o separati) per gestire l'interfaccia utente del POC.
Il componente deve includere:
1. Un Form reattivo (ReactiveFormsModule) con i controlli validati per: Partenza, Destinazione e Budget Massimo.
2. Un pulsante di invio che, al clic, invoca il TravelService per avviare il processo, memorizza il Job ID e attiva l'ascolto dello stream SSE.
3. Una sezione centrale di caricamento che visualizza in tempo reale una barra di avanzamento e i log testuali dinamici ricevuti dallo stream SSE (es. "Fase 1: Mappatura itinerario calcolata...").
4. Una sezione finale di successo, visibile solo al termine dello stream, che esegue il parsing del JSON finale e mostra l'output organizzato in 3 card affiancate che rappresentano le 3 alternative di viaggio ottimizzate ("Volo Low Cost + Navetta", "Treno Intercity", "Carpooling/Flixbus"), evidenziando i costi ed i dettagli.
```
