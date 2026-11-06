# Analisi del Prompt 7

Prompt esaminato: `prompt_github_copilot.md`, sezione “Prompt 7: Implementazione del Bridge MCP su Spring AI”.

## Valutazione

- **R1 — Singolo task: conforme.** Il prompt chiede di esporre la funzionalità di ottimizzazione come un unico tool MCP sincrono.
- **R2 — Impatto limitato: conforme, con una riserva.** La nuova classe `TravelMcpServerTools` è il principale oggetto da implementare e l'engine e i DTO necessari esistono già. Il prompt, però, non delimita esplicitamente i file modificabili: precisare che si devono riutilizzare i componenti esistenti e limitare le modifiche alla nuova classe e ai relativi test eviterebbe interventi non necessari.
- **R3 — Ambito specifico: conforme.** L'ambito è l'esposizione delle funzionalità di ottimizzazione tramite il server MCP. Il coordinamento con l'engine è una dipendenza di questo bridge, non un ambito funzionale separato.
- **R4 — Non del tutto conforme.** Alcune scelte essenziali sono lasciate aperte e possono portare a implementazioni incompatibili.

Non occorre suddividere il prompt: descrive un'attività unica e circoscritta.

## Domande per chiarire il prompt

1. **File modificabili:** confermi che l'implementazione deve limitare le modifiche a `TravelMcpServerTools` e ai relativi test, riutilizzando `TravelOptimizationEngine` e i DTO già presenti senza modificare il trasporto MCP o la persistenza?
2. **Registrazione del tool:** quale meccanismo va usato per registrare il tool MCP con le dipendenze del progetto: annotazione Spring AI o `ToolCallback`? Come deve essere esposto dal server MCP?
3. **Avvio e attesa del job:** il bridge deve invocare `TravelOptimizationEngine` con un ID univoco e attendere il completamento interrogando le API di stato e risultato già disponibili? Quale intervallo di polling e quale timeout massimo si devono applicare?
4. **Completamento e anomalie:** quale stato identifica il completamento? Se il job scade, viene interrotto o termina senza payload, il tool deve restituire un errore MCP o un risultato specifico? Come vanno propagati gli input non validi e gli errori dell'engine?
5. **Formato della risposta:** il client MCP deve ricevere `TravelOptionsResponse` come testo JSON oppure come contenuto strutturato? In entrambi i casi, è richiesto un particolare schema o nome dei campi?

La formulazione “bloccare il thread corrente in modo non allocativo” andrebbe inoltre chiarita: l'attesa sincrona mantiene l'invocazione in attesa, mentre l'eventuale strategia per contenere il consumo di risorse va specificata separatamente.

## Indicazione per la revisione del prompt

Mantenere l'obiettivo del bridge MCP sincrono e specificare i punti sopra, senza estendere il lavoro alla configurazione del trasporto MCP, all'implementazione dell'engine o alla persistenza, che sono ambiti distinti.
