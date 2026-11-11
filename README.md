# travel-budget-optimizer (One Jar Deliverable)

che, date due località (partenza e destinazione) e un budget di spesa massimo stabilito dall'utente, sia in grado di generare e confrontare dinamicamente alternative di viaggio ottimizzate

## Indicazioni tecniche

Progetto Java 25 gestito con Apache Maven e Spring Boot 3.5.16 per
l'ottimizzazione del budget di viaggio.
Il progetto contiene una struttura iniziale; la logica di ottimizzazione non
e' ancora implementata.

Il `pom.xml` include gli starter Web (REST/SSE), Security e MCP Server WebMVC,
con Spring AI 1.1.8 e LangChain4j 1.23.0 gestiti tramite i rispettivi BOM.
`spring-ai-starter-mcp-server-webmvc` e' il nome stabile dello starter HTTP/SSE:
il vecchio `spring-ai-mcp-server-spring-boot-starter` era una milestone,
non una release stabile. `langchain4j-core` include `@Tool` e le API dei tool;
`langchain4j` include gli AI Services per il Function Calling.

### Requisiti

- JDK 25 o successivo
- Apache Maven 3.8.7 o successivo

### Compilazione e test

```sh
mvn clean verify
```

Il codice viene compilato per Java 25 tramite `maven.compiler.release`, con
`source` e `target` allineati e nomi dei parametri conservati (`-parameters`).
Il plugin Spring Boot produce un JAR eseguibile con le dipendenze incluse.
Maven installa anche Node in `target`, esegue `npm ci` e la build Angular,
quindi include il frontend nelle risorse statiche del JAR. Node e npm non
sono necessari sulla macchina di esecuzione.

### Avvio

```sh
java -jar target/travel-budget-optimizer-1.0.0-SNAPSHOT.jar
```

Aprire `http://localhost:8080`: lo stesso processo Java serve frontend, API REST
e server MCP. Il server Angular separato e' necessario solo per lo sviluppo UI.

### Limiti delle risorse

I job REST e MCP condividono un massimo di 1000 risultati conservati per 10
minuti dall'accettazione. I job scaduti vengono rimossi alla lettura o al
successivo invio. L'esecuzione usa due worker e una coda di 32 job; quando la
capacita' e' esaurita, REST restituisce HTTP 429 e MCP `TOO_MANY_REQUESTS`.
Le localita' non possono superare 256 caratteri.

### Dettagli delle proposte

La richiesta REST `/api/viaggi/ottimizza` accetta facoltativamente `dataPartenza`
in formato ISO (`yyyy-MM-dd`); le richieste precedenti senza questo campo
restano valide. Le proposte mantengono mezzo, costo, durata e descrizione e
possono includere `dettaglioViaggio` con tratte, orari, fermate, vettori e
fonti. I dettagli sono indicativi, non costituiscono disponibilita' o offerte
prenotabili; se una fonte non restituisce dati, il campo viene mostrato come
non disponibile e le tre proposte con i valori esistenti restano utilizzabili.

Gli aggiornamenti SSE usano due worker condivisi, un massimo di 100 connessioni
e un timeout di 60 secondi. Un job sconosciuto o scaduto restituisce HTTP 404;
il superamento del limite di connessioni restituisce HTTP 429.

Gli endpoint REST e MCP rimangono pubblici per il POC: prima di esporli su
Internet, aggiungere autenticazione.

Il processo Java applica anche limiti di ammissione per indirizzo IP: le POST
di ottimizzazione REST, tutte le POST MCP e le aperture SSE MCP condividono
20 richieste al minuto e 100 ogni 10 minuti, in finestre mobili. Anche le
richieste ammesse che successivamente falliscono consumano la quota.
Le risposte HTTP 429 includono `Retry-After`. Il registro degli IP e' limitato
a 1024 voci e scade dopo 10 minuti senza richieste ammesse ne' sessioni attive.

Le sessioni SSE MCP hanno un limite di 100 connessioni complessive e 5 per IP.
Ogni sessione dura al massimo 120 secondi; i client MCP devono riconnettersi
alla scadenza. La proprieta' `security.admission.mcp-session-timeout-ms`
consente di modificare questa durata, che deve essere positiva.

I limiti usano l'IP della connessione, non gli header `X-Forwarded-For`
forniti dal client. Gli utenti dietro lo stesso NAT o reverse proxy condividono
la quota: per una distribuzione multiutente usare identita' autenticate e una
configurazione fidata del proxy. Non sono limiti per identita' e non fermano
un attacco distribuito. Tutti i controlli risiedono nel JAR: non richiedono un
processo Node, un database o un reverse proxy per avviare frontend e backend.
