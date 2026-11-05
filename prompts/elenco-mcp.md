Per eliminare la gestione dei sottoprocessi locali (stdio / pipe) senza ricorrere alla containerizzazione Docker, la strategia ottimale consiste nel sostituire i server MCP nativi con equivalenti architetture basate su API HTTP/REST o trasporti nativi SSE (Server-Sent Events) del Model Context Protocol.
Nel 2026, l'ecosistema MCP e i fornitori di API cloud si sono evoluti per esporre direttamente endpoint compatibili con il trasporto HTTP/SSE di MCP, facilitando l'integrazione nativa in Java tramite client HTTP standard o le librerie incluse in LangChain4j/Spring AI.
Di seguito viene presentata la tabella di mappatura che sostituisce i server via pipe con soluzioni contattabili esclusivamente via HTTP / HTTPS / SSE:

Servizio originale (Pipe / stdio)	Sostituto compatibile HTTP / SSE	Tipo di protocollo / Integrazione	Descrizione della soluzione senza Docker
Sequential Thinking / Brave Search MCP	Brave Search API Nativi o Perplexity API	HTTP REST / JSON standard	Invece di un server MCP node locale che fa scraping o interroga Brave via pipe, l'LLM invoca uno strumento Java (@Tool di LangChain4j) che effettua una chiamata HTTP standard verso l'endpoint ufficiale di Brave o Perplexity Sonar per ottenere dati web aggiornati in tempo reale.
Fetch MCP (Anthropic)	Jina Reader API (https://jina.ai) o Firecrawl API	HTTP GET / REST	Sostituisce il server Fetch locale. Fornisce un endpoint pubblico HTTP a cui inviare qualsiasi URL. Restituisce il contenuto della pagina web già pulito e formattato in Markdown, perfetto per essere digerito dall'LLM senza elaborazioni locali.
Google Maps MCP	Google Maps Platform Cloud API	HTTP REST (Web Services)	Il server MCP locale via pipe viene sostituito dalle chiamate HTTP dirette alle API cloud di Google (Directions API, Distance Matrix API). Si integrano nativamente in Java tramite l'SDK ufficiale di Google o semplici client HTTP, calcolando percorsi e tempi di percorrenza.
OpenStreetMap / GraphHopper MCP	GraphHopper Directions API Cloud	HTTP REST	Alternativa open-source a Google Maps. GraphHopper offre un servizio cloud pubblico a consumo. Il backend Java effettua una richiesta HTTP POST per ottenere il calcolo delle distanze e le tracce geografiche senza far girare l'eseguibile a terra.
Puppeteer MCP	Browserless.io o ScrapingBee API	HTTP / WebSocket (Cloud)	Puppeteer richiede un browser headless (Chrome) che non può girare agevolmente dentro la JVM senza Docker. Utilizzando servizi terzi in cloud come Browserless.io, il backend Java effettua una chiamata HTTP/WebSocket a un'infrastruttura remota che esegue lo scraping dei siti di hotel/voli e restituisce i dati strutturati.
SQLite MCP	Neon Postgres o Supabase o Chroma / Pinecone Cloud	HTTP / TCP standard	Se non si vuole gestire un file SQLite locale via server MCP, si migra la persistenza del prototipo su un database cloud serverless con piano gratuito (come Neon per il relazionale o Pinecone per i vettori). L'LLM interagisce con questi database tramite chiamate HTTP/REST o driver JDBC standard nativi in Java.


Come cambia l'architettura in Java (LangChain4j / Spring AI)

Eliminando i server MCP via pipe e Docker, il backend Spring Boot non ha più bisogno di orchestrare processi esterni. L'orchestrazione si sposta interamente all'interno del codice Java sfruttando il pattern dei Tools (Function Calling) di LangChain4j.
Ecco come si traduce l'architettura a livello di codice:
1. Nessun trasporto Stdio: LangChain4j non configurerà oggetti di tipo StdioMcpClient.
2. Uso di @Tool nativi: Le funzionalità che prima erano delegate ai server MCP via pipe vengono riscritte come componenti Spring (Service) e annotate con @Tool di LangChain4j. All'interno di questi metodi verranno usati i client HTTP nativi di Java 25 (HttpClient) o i RestClient di Spring Boot per interrogare le API cloud sopra elencate.
3. Gateway Spring AI: Spring AI riceve le richieste di configurazione dei flussi e agisce come layer espositivo e di routing, mentre LangChain4j si occupa di impacchettare questi strumenti HTTP e passarli al modello (LLM) durante la sessione asincrona SSE.
Per procedere con lo sviluppo del prototipo, preferisci che ti mostri:
* Come implementare un componente @Tool in LangChain4j che interroga un servizio di scraping cloud (es. Jina Reader o Browserless) via HTTP?
* Come configurare l'endpoint Spring Boot Controller con risposte asincrone SSE per gestire i lunghi tempi di attesa delle API esterne senza bloccare Angular?
* La struttura del file Maven (pom.xml) per far coesistere i layer di Spring AI e LangChain4j sotto Java 25?
