import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface TravelOption {
    mezzo: string;
    costoTotalizzato: number;
    tempoPercorrenza: string;
    descrizioneDettagliata: string;
    dettaglioViaggio?: TravelItineraryDetails;
}

export interface TravelItineraryDetails {
    tratte: TravelLeg[];
    fonti: string[];
    indicativo: boolean;
}

export interface TravelLeg {
    partenza: string | null;
    destinazione: string | null;
    orarioPartenza: string | null;
    orarioArrivo: string | null;
    vettore: string | null;
    fermate: string[];
}

export interface TravelOptionsResponse {
    opzioniViaggio: TravelOption[];
}

export type TravelSseEvent =
    | { type: 'status'; message: string }
    | { type: 'result'; result: TravelOptionsResponse }
    | { type: 'timeout'; message: string };

export interface TravelOptimizationRequest {
    partenza: string;
    destinazione: string;
    budgetMassimo: number;
    dataPartenza?: string;
}

export interface TravelOptimizationAccepted {
    jobId: string;
}

@Injectable({ providedIn: 'root' })
export class TravelService {
    private readonly http = inject(HttpClient);
    private readonly apiUrl = '/api/viaggi';

    avviaRichiestaOttimizzazione(
        partenza: string,
        destinazione: string,
        budgetMassimo: number,
        dataPartenza?: string,
    ): Observable<TravelOptimizationAccepted> {
        const request: TravelOptimizationRequest = {
            partenza,
            destinazione,
            budgetMassimo,
            ...(dataPartenza ? { dataPartenza } : {}),
        };

        return this.http.post<TravelOptimizationAccepted>(
            `${this.apiUrl}/ottimizza`,
            request,
        );
    }

    ascoltaAggiornamentiStatus(jobId: string): Observable<TravelSseEvent> {
        return new Observable<TravelSseEvent>((subscriber) => {
            const eventSource = new EventSource(
                `${this.apiUrl}/status/${encodeURIComponent(jobId)}`,
            );

            const onStatus = (event: Event): void => {
                subscriber.next({
                    type: 'status',
                    message: (event as MessageEvent<string>).data,
                });
            };

            const onResult = (event: Event): void => {
                try {
                    const result = parseTravelOptionsResponse(
                        (event as MessageEvent<string>).data,
                    );
                    subscriber.next({ type: 'result', result });
                    eventSource.close();
                    subscriber.complete();
                } catch (error) {
                    eventSource.close();
                    subscriber.error(error);
                }
            };

            const onTimeout = (event: Event): void => {
                subscriber.next({
                    type: 'timeout',
                    message: (event as MessageEvent<string>).data,
                });
                eventSource.close();
                subscriber.complete();
            };

            const onError = (): void => {
                // EventSource retries automatically while CONNECTING; surface only a terminal failure.
                if (eventSource.readyState === EventSource.CLOSED) {
                    eventSource.close();
                    subscriber.error(new Error('Connessione SSE chiusa prima del risultato.'));
                }
            };

            eventSource.addEventListener('status', onStatus);
            eventSource.addEventListener('result', onResult);
            eventSource.addEventListener('timeout', onTimeout);
            eventSource.addEventListener('error', onError);

            return () => {
                eventSource.removeEventListener('status', onStatus);
                eventSource.removeEventListener('result', onResult);
                eventSource.removeEventListener('timeout', onTimeout);
                eventSource.removeEventListener('error', onError);
                eventSource.close();
            };
        });
    }
}

export function parseTravelOptionsResponse(payload: string): TravelOptionsResponse {
    const parsed: unknown = JSON.parse(payload);
    if (
        typeof parsed !== 'object' ||
        parsed === null ||
        !('opzioniViaggio' in parsed) ||
        !Array.isArray(parsed.opzioniViaggio)
    ) {
        throw new Error('Il risultato SSE non rispetta il formato atteso.');
    }

    const opzioniViaggio = parsed.opzioniViaggio.map((option): TravelOption => {
        if (
            typeof option !== 'object' ||
            option === null ||
            !('mezzo' in option) ||
            typeof option.mezzo !== 'string' ||
            !('costoTotalizzato' in option) ||
            typeof option.costoTotalizzato !== 'number' ||
            !('tempoPercorrenza' in option) ||
            typeof option.tempoPercorrenza !== 'string' ||
            !('descrizioneDettagliata' in option) ||
            typeof option.descrizioneDettagliata !== 'string'
        ) {
            throw new Error('Un’opzione di viaggio non rispetta il formato atteso.');
        }

        const detail = 'dettaglioViaggio' in option ? option.dettaglioViaggio : undefined;
        if (detail === undefined || detail === null) {
            return {
                mezzo: option.mezzo,
                costoTotalizzato: option.costoTotalizzato,
                tempoPercorrenza: option.tempoPercorrenza,
                descrizioneDettagliata: option.descrizioneDettagliata,
            };
        }
        if (
            typeof detail !== 'object' ||
            detail === null ||
            !('tratte' in detail) ||
            !Array.isArray(detail.tratte) ||
            !('fonti' in detail) ||
            !isStringArray(detail.fonti) ||
            !('indicativo' in detail) ||
            typeof detail.indicativo !== 'boolean'
        ) {
            throw new Error('I dettagli dell’itinerario non rispettano il formato atteso.');
        }

        const tratte = detail.tratte.map(parseTravelLeg);
        return {
            mezzo: option.mezzo,
            costoTotalizzato: option.costoTotalizzato,
            tempoPercorrenza: option.tempoPercorrenza,
            descrizioneDettagliata: option.descrizioneDettagliata,
            dettaglioViaggio: {
                tratte,
                fonti: detail.fonti,
                indicativo: detail.indicativo,
            },
        };
    });

    return { opzioniViaggio };
}

function parseTravelLeg(value: unknown): TravelLeg {
    if (!isRecord(value) || !isStringArray(value['fermate'])) {
        throw new Error('Una tratta non rispetta il formato atteso.');
    }
    const fermate = value['fermate'];

    const partenza = optionalNullableString(value, 'partenza');
    const destinazione = optionalNullableString(value, 'destinazione');
    const orarioPartenza = optionalNullableString(value, 'orarioPartenza');
    const orarioArrivo = optionalNullableString(value, 'orarioArrivo');
    const vettore = optionalNullableString(value, 'vettore');
    if ([partenza, destinazione, orarioPartenza, orarioArrivo, vettore].includes(undefined)) {
        throw new Error('Una tratta non rispetta il formato atteso.');
    }

    return {
        partenza: partenza ?? null,
        destinazione: destinazione ?? null,
        orarioPartenza: orarioPartenza ?? null,
        orarioArrivo: orarioArrivo ?? null,
        vettore: vettore ?? null,
        fermate,
    };
}

function optionalNullableString(
    object: Record<string, unknown>,
    field: string,
): string | null | undefined {
    if (!(field in object)) {
        return null;
    }
    const value = object[field];
    return value === null || typeof value === 'string' ? value : undefined;
}

function isStringArray(value: unknown): value is string[] {
    return Array.isArray(value) && value.every((item: unknown) => typeof item === 'string');
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
