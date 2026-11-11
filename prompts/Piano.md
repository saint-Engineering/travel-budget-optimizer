# Piano: descrizione LLM di fallback per i viaggi

## Problema

Quando i servizi di ricerca non forniscono dettagli verificabili, le proposte di viaggio mantengono descrizioni statiche. L'applicazione deve poter generare una descrizione narrativa in italiano usando partenza, destinazione e mezzo di trasporto, senza presentare come certi orari, fermate, prezzi o disponibilità non supportati da fonti.

## Approccio proposto

- Mantenere separati i dettagli fattuali del viaggio (`dettaglioViaggio`, con tratte e fonti) dalla descrizione narrativa.
- Riutilizzare l'integrazione Perplexity esistente per generare la descrizione, con input tipizzato per partenza, destinazione e mezzo. Passare anche la data di partenza quando già disponibile, senza renderla obbligatoria.
- Invocare il generatore solo per ciascun mezzo per cui non ci sono dettagli utili e verificati. Non sostituire né alterare dettagli già restituiti dai servizi esistenti.
- Inserire il testo in `descrizioneDettagliata` di `TravelOption` con un'etichetta esplicita, ad esempio "Descrizione generata (indicativa):". Non modificare lo schema API; mantenere i dettagli strutturati vuoti quando non verificati.
- Istruire il modello a produrre testo indicativo, in italiano, e a non inventare informazioni operative. In caso di errore o indisponibilità del provider, conservare il comportamento di fallback corrente e registrare l'errore.
- Separare la generazione narrativa dal parsing dei dettagli fattuali: una risposta narrativa non deve essere scartata solo perché non contiene citazioni; i dettagli strutturati continuano invece a richiedere fonti.
- Aggiungere test mirati per fallback per mezzo, preservazione dei dettagli esistenti, generazione narrativa senza citazioni e indisponibilità/configurazione assente del provider.
- Aggiornare la documentazione direttamente interessata e salvare una copia del piano in `prompts/Piano.md` dopo l'approvazione del piano.

## Componenti da modificare

- `TravelOptimizationEngine`: orchestrazione del fallback per le opzioni generate.
- `CloudTravelTools`: chiamata a Perplexity e gestione separata della risposta narrativa rispetto ai dettagli verificati.
- `TravelOption`: mantenere lo schema API e contrassegnare la descrizione generata con un'etichetta nel testo.
- Test Java e README: verificare i comportamenti e documentare il limite informativo.
- `prompts/Piano.md`: copia di questo piano richiesta dall'utente, da creare dopo l'approvazione.

## Decisioni confermate

- Riutilizzare l'integrazione Perplexity esistente.
- Generare la descrizione solo quando mancano dettagli verificati per quel mezzo.
- Indicare la provenienza con un'etichetta nel testo, senza aggiungere campi alla risposta API.

## Attenzioni

- Le citazioni devono continuare a essere richieste per i dettagli fattuali, ma non devono impedire la visualizzazione di una descrizione narrativa generata.
- La descrizione non deve essere presentata come un itinerario reale o prenotabile.
- Non esporre chiavi API nel codice; usare la configurazione esterna già adottata dal progetto.
