# Requisiti di Sistema: Servizio di Ottimizzazione Budget Viaggi (Architettura MCP HTTP/SSE)

## Obiettivo del Servizio
Realizzare un prototipo di servizio (POC) che, date due località (partenza e destinazione) e un budget di spesa massimo stabilito dall'utente, sia in grado di generare e confrontare dinamicamente alternative di viaggio ottimizzate.

## Architettura di Riferimento e Protocolli
Per eliminare la gestione dei sottoprocessi locali (`stdio`/pipe) e non potendo ricorrere a containerizzazione Docker, l'orchestrazione dei componenti esterni si basa esclusivamente sul **Model Context Protocol (MCP)** implementato tramite trasporti **HTTP, HTTPS e SSE (Server-Sent Events)**, oppure integrato a livello di codice Java tramite il pattern dei **Tools (Function Calling)**.

### Mappatura e Sostituzione Server MCP (da Pipe a HTTP/SSE)
Il sistema abbandona i server eseguiti via riga di comando a favore delle seguenti controparti cloud e web-service:

1. **Ricerca Spostamenti e Tariffe (Voli, Treni, Bus)**
   * **Brave Search API / Perplexity API:** Sostituisce *Sequential Thinking/Brave via Pipe*. L'LLM invoca uno strumento Java nativo per effettuare chiamate HTTP standard verso l'endpoint ufficiale di Brave o Perplexity Sonar per estrarre tariffe attuali (es. Trenitalia, Flixbus) e costi carburante aggiornati in tempo reale.
   * **Jina Reader API / Firecrawl API:** Sostituisce *Fetch MCP*. Fornisce un endpoint pubblico HTTP GET a cui inviare gli URL dei siti di comparazione. Restituisce il contenuto delle pagine web pulito e formattato in Markdown per l'analisi immediata dei prezzi.

2. **Calcolo Itinerari e Distanze Stradali**
   * **Google Maps Platform Cloud API / GraphHopper Directions API Cloud:** Sostituisce i rispettivi server locali via pipe. Il backend effettua richieste HTTP REST (Web Services) per calcolare distanze esatte, tempi di percorrenza e tracce geografiche direttamente da Java senza runtime locali.

3. **Alloggi e Costo della Vita**
   * **Browserless.io / ScrapingBee API:** Sostituisce *Puppeteer MCP* (il quale richiederebbe un browser headless Chrome non gestibile in JVM senza Docker). Il backend Java delega lo scraping dinamico dei portali di alloggi a un'infrastruttura remota via HTTP/WebSocket, ricevendo dati pronti e strutturati.

4. **Persistenza e Storico**
   * **Neon Postgres / Supabase / Pinecone Cloud:** Sostituisce *SQLite MCP*. Interazione tramite chiamate HTTP/REST o driver JDBC standard per salvare le ricerche, verificare combinazioni storiche e ottimizzare le risposte.

---

## Vincoli Tecnologici Coerenti (Scenario 2026)
* **Linguaggio:** Java >= 25 (Versione Stabile LTS).
* **Build Tool:** Apache Maven.
* **Framework Core:** Spring Boot per le logiche di backend e l'ecosistema applicativo.
* **Framework Intelligenza Artificiale:** LangChain4j per la gestione dell'interazione con l'LLM e per il wrapping delle API esterne tramite l'annotazione `@Tool` (Function Calling).
* **Gateway Espositivo:** Spring AI per l'esposizione, il routing dei flussi e la gestione dei messaggi.
* **Interfaccia di Comunicazione:** Servizi esposti tramite API REST asincrone.
* **Frontend:** Applicazione web "POC" sviluppata in Angular.

---

## Flusso di Lavoro Asincrono del Servizio (Workflow)

Data la natura pesante delle elaborazioni e delle chiamate HTTP a servizi di scraping ed itinerari cloud (che possono richiedere tra i 15 e i 30 secondi), il sistema non utilizzerà chiamate REST sincrone bloccanti onde evitare timeout lato browser. Il flusso è strutturato come segue:

1. **Input Utente:** L'utente inserisce i dati tramite il frontend Angular (Es: *Partenza: Milano, Destinazione: Parigi, Budget: 150€*).
2. **Inizio Sessione (REST Asincrono):** Angular effettua una chiamata `POST` al backend Spring Boot. Il controller risponde immediatamente con uno stato `202 Accepted` restituendo un identificativo univoco (`Job ID`).
3. **Apertura Canale SSE:** Il frontend Angular apre un canale di ascolto **SSE (Server-Sent Events)** verso l'endpoint di stato dedicato (es. `/api/viaggi/status/{jobId}`).
4. **Esecuzione e Streaming dei Progressi (Backend & LLM):**
   * **Fase 1 (Mappatura):** L'LLM invoca il Tool di Google Maps/GraphHopper via HTTP REST. Invia un evento SSE ad Angular: *"Fase 1: Distanza calcolata..."*.
   * **Fase 2 (Verifica Costi):** L'LLM interroga in parallelo le API di Brave Search, Jina Reader e Browserless.io per raccogliere i costi reali di voli, treni ed alloggi. Invia un evento SSE: *"Fase 2: Tariffe trasporti e hotel identificate..."*.
   * **Fase 3 (Filtro Budget & Salvataggio):** L'LLM analizza i dati, scarta le opzioni sopra budget e salva i dati nel DB Cloud (Neon/Supabase).
5. **Output Finale:** Il backend invia l'evento SSE conclusivo contenente il payload JSON con le 3 alternative ottimizzate (es: *"Volo Low Cost + Navetta"*, *"Treno Intercity"*, *"Carpooling/Flixbus"*), chiudendo la connessione in modo pulito.

## Esposizione come server MCP

Il servizio offerto deve esporre anche un server MCP per facilitare l'integrazione all'interno di soluzioni IA di terzi. Il POC del servizio avrà quindi questi end point

- front end angular (interazione diretta degli utenti)
- servizi rest
- server MCP esposto

## esempio di Flusso di Lavoro (Workflow) del Servizio

1. Input: L'utente chiede un viaggio da Milano a Parigi con un budget di 150€.
2. Fase 1 (Mappatura): Il server Google Maps MCP calcola la distanza e le opzioni disponibili (Aereo, Treno, Auto).
3. Fase 2 (Verifica Costi):
	* Il server Web Search/Fetch MCP cerca i prezzi dei voli low-cost (es. RyanAir/EasyJet) e dei treni (TGV/Frecciarossa) per quelle date.
	* Un calcolatore interno stima il costo della benzina + pedaggi per l'opzione auto.
4. Fase 3 (Filtro Budget nel DB): L'LLM usa SQLite MCP per verificare se ci sono combinazioni storiche o per salvare le opzioni trovate sotto i 150€.
5. Output: Il servizio presenta 3 alternative: "Volo Low Cost (80€) + Navetta", "Treno Intercity (110€, più lento ma centrale)", "Carpooling/Flixbus (45€, l'opzione più economica)".
