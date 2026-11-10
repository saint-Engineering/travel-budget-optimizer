
# travel-budget-optimizer (single file delivery)

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

### Avvio

```sh
java -jar target/travel-budget-optimizer-1.0.0-SNAPSHOT.jar
```

L'attuale `App` e' ancora un punto di ingresso dimostrativo: non avvia un
contesto Spring Boot o un server HTTP. Il POM prepara le dipendenze; bootstrap,
endpoint MCP, provider LLM e regole di autenticazione/CORS/CSRF vanno implementati
nell'applicazione.
