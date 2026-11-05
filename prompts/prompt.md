Dobbiamo creare un servizio che generi alternative di viaggio tra due località in base a un budget stabilito con architettura basata su Model Context Protocol (MCP). Ci sono stati forniti questi MCP gratuiti che ci servono per il prototipo.

1. Ricerca Voli, Treni e Alternative di Viaggio

Per trovare le opzioni di trasporto reali e i relativi costi, hai bisogno di server MCP che possano navigare sul web o interfacciarsi con API di viaggio.
* Sequential Thinking MCP / Web Search MCP (Community / Brave Search): Consente all'LLM di effettuare ricerche mirate sul web per estrarre tariffe attuali di voli, treni (es. Trenitalia, Italo, Flixbus) o stimare i costi del carburante. E` fondamentale per avere dati aggiornati dell'ultima ora senza pagare costose API commerciali.
* Fetch MCP (Anthropic): Un server che permette all'LLM di "leggere" direttamente il contenuto HTML/Markdown di specifiche pagine web (come i siti di comparazione viaggi o blog di itinerari) per estrarre i prezzi delle tratte e confrontarli.

2. Calcolo Itinerari, Distanze e Costi Stradali (Auto/Bus)

Se l'utente preferisce viaggiare su strada, è necessario calcolare i percorsi e stimare i costi di pedaggio e carburante.
* Google Maps MCP o OpenStreetMap/GraphHopper MCP: Permettono di calcolare la distanza esatta tra le due località, i tempi di percorrenza e le modalità di trasporto (auto, mezzi pubblici, bici). Integrando le distanze con il costo medio del carburante, il tuo servizio può calcolare l'alternativa di viaggio "on the road" e verificare se rientra nel budget.

3. Alloggi e Costo della Vita (Ottimizzazione Budget)

Un viaggio non è fatto solo dallo spostamento. Per capire quanto del budget rimarrà per il soggiorno, servono dati di contesto.
* Puppeteer MCP: Consente di automatizzare la navigazione web in modalità "headless". Puoi usarlo per fare scraping dinamico (rispettando i termini di servizio) sui principali portali di hotel o ostelli per ottenere una stima del costo a notte nelle località di destinazione.

Infine, un esempio di Flusso di Lavoro (Workflow) del Servizio

1. Input: L'utente chiede un viaggio da Milano a Parigi con un budget di 150€.
2. Fase 1 (Mappatura): Il server Google Maps MCP calcola la distanza e le opzioni disponibili (Aereo, Treno, Auto).
3. Fase 2 (Verifica Costi):
	* Il server Web Search/Fetch MCP cerca i prezzi dei voli low-cost (es. RyanAir/EasyJet) e dei treni (TGV/Frecciarossa) per quelle date.
	* Un calcolatore interno stima il costo della benzina + pedaggi per l'opzione auto.
4. Fase 3 (Filtro Budget nel DB): L'LLM usa SQLite MCP per verificare se ci sono combinazioni storiche o per salvare le opzioni trovate sotto i 150€.
5. Output: Il servizio presenta 3 alternative: "Volo Low Cost (80€) + Navetta", "Treno Intercity (110€, più lento ma centrale)", "Carpooling/Flixbus (45€, l'opzione più economica)".

Valgono i seguenti vincoli tecnologici

- Java >= 25 ma sempre LTS (Nota: siamo nel 2026)
- maven per il build
- SpringBoot come tecnologia di supporto per il backend
- LangChain4j per quello che riguarda la parte collegata alla IA (interazione LLM, interrogazione server MCP di terze parti)
- Servizi esposti via rest
- Gateway MCP verso i nostri servizi realizzati con SpringAI
- Font end "POC" in Angular


3. Timeout e Blocco dei Servizi REST Sincroni

* La criticità: Il flusso prevede passaggi sequenziali estremamente pesanti (Calcolo itinerari con Google Maps, web scraping live con Puppeteer e ricerche multiple sul web tramite Brave). Una singola richiesta HTTP REST sincrona proveniente da Angular rischia di rimanere appesa per 15-30 secondi prima di elaborare tutte le alternative. Questo scenario causerà sistematicamente timeout nel browser, un consumo eccessivo di risorse sul backend e una pessima esperienza utente.
* Mitigazione: Sfruttate le novità di Java 25 (ottimizzazione dei Virtual Threads / Project Loom) accoppiate a una comunicazione asincrona. L'endpoint REST di Spring Boot deve rispondere immediatamente con uno stato 202 Accepted e un ID di sessione. Il frontend Angular aprirà poi un canale SSE (Server-Sent Events) o un WebSocket per ricevere gli aggiornamenti in tempo reale dall'LLM man mano che i singoli server MCP restituiscono i dati (es. "Fase 1: Mappa completata..." ? "Fase 2: Voli trovati..."
