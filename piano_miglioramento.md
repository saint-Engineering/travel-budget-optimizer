# Prompt per GitHub Copilot: descrizioni complete e affidabili dei viaggi

Implementa nel repository una correzione per le descrizioni delle opzioni di viaggio, seguendo i requisiti qui sotto. Prima di modificare il codice, verifica l'implementazione attuale e i test esistenti; limita le modifiche a questo comportamento e alle verifiche direttamente correlate.

## Contesto verificato

- `TravelOptimizationEngine` crea le tre opzioni (treno, autobus e aereo) e al momento assegna a `descrizioneDettagliata` testi fissi che riportano solo partenza e destinazione.
- Lo stesso engine recupera i dati tramite `CloudTravelTools.cercaDettagliViaggio(...)` e li associa a `dettaglioViaggio`, ma non li usa per costruire la descrizione.
- I dettagli strutturati sono rappresentati da `TravelItineraryDetails` e `TravelLeg`. Possono contenere tratte, località di partenza e arrivo, orari, vettore e fermate.
- I provider possono non restituire dettagli (per esempio con chiavi non configurate, risposte senza dati utilizzabili o errori); l'engine può quindi proseguire con una mappa vuota.
- In `CloudTravelTools` ci sono metodi annotati `@Tool`, ma l'attuale flusso di ottimizzazione non li usa come un agente LLM per generare descrizioni.

## Obiettivo e ambito

1. Costruisci `descrizioneDettagliata` in modo deterministico a partire dai dati realmente disponibili per ciascun mezzo, usando i dettagli della relativa `TravelOption`.
2. Usa i campi presenti senza inventare informazioni: tratta, partenza, destinazione, orari, vettore e fermate. Includi solo i dettagli valorizzati e utili; mantieni una descrizione leggibile e in italiano.
3. Quando i dettagli della tratta sono assenti o incompleti, restituisci comunque una descrizione non vuota, chiaramente indicativa e trasparente sul fatto che i dettagli non sono disponibili. Non presentare stime o valori simulati come dati verificati da un provider.
4. Mantieni distinti i dati osservati e quelli indicativi. In particolare, verifica l'origine di costo e durata prima di descriverli come reali o confermati.
5. Mantieni il comportamento asincrono, gli stati del job, la struttura della risposta MCP/JSON e la compatibilità dei payload esistenti. Non cambiare il numero di opzioni o i nomi dei campi pubblici.
6. Non aggiungere in questa implementazione generazione LLM, un agente o nuove invocazioni a `@Tool`: sono fuori scope. Evita nuove dipendenze e chiamate di rete per comporre la descrizione.

## Requisiti di implementazione

- Individua il punto più adatto nel flusso esistente per comporre le descrizioni dopo il recupero dei dettagli, riutilizzando le strutture e le convenzioni già presenti.
- Associa i dettagli al mezzo corretto senza assegnare una tratta di un mezzo a un'altra opzione. Verifica le chiavi/canonicalizzazioni dei mezzi in `CloudTravelTools` prima di implementare l'associazione.
- Gestisci in modo esplicito campi null, stringhe vuote e liste vuote. Non produrre separatori, etichette o informazioni vuote nella descrizione.
- Conserva `dettaglioViaggio` e i relativi dati: la descrizione li integra, non li sostituisce.
- Mantieni error handling e logging coerenti con il progetto. Non nascondere nuovi errori con fallback silenziosi e non aggiungere log ripetitivi per normali assenze di dati.

## Test richiesti

Aggiungi o aggiorna test automatici, senza dipendere da servizi esterni, che verifichino almeno:

- descrizione costruita con una o più tratte complete e con i dettagli corrispondenti al mezzo;
- descrizione sensata quando alcuni campi sono null/vuoti o mancano fermate;
- fallback non vuoto e dichiaratamente indicativo quando non ci sono dettagli per un mezzo o la mappa è vuota;
- nessuna contaminazione tra i dettagli di treno, autobus e aereo;
- serializzazione e recupero del risultato MCP compatibili con la struttura JSON già esposta.

Esegui i test mirati relativi a engine, dettagli e tool MCP; se falliscono, correggi le regressioni causate dalla modifica. Riporta nel riepilogo finale i file modificati e i comandi di test eseguiti con il relativo esito.

## Criteri di accettazione

- Ogni opzione completata espone una descrizione utile e non vuota.
- Se sono disponibili dati di tratta, la descrizione li riassume fedelmente; se non lo sono, non inventa un itinerario.
- Le descrizioni non dipendono da un LLM, da un `@Tool` o da accessi di rete aggiuntivi.
- Restano invariati il contratto MCP/JSON e il flusso di stato asincrono.
- I test aggiunti coprono i casi con dati presenti, parziali e assenti.
