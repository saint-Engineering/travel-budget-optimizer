import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface TravelOption {
    mezzo: string;
    costoTotalizzato: number;
    tempoPercorrenza: string;
    descrizioneDettagliata: string;
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
    ): Observable<TravelOptimizationAccepted> {
        const request: TravelOptimizationRequest = {
            partenza,
            destinazione,
            budgetMassimo,
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

function parseTravelOptionsResponse(payload: string): TravelOptionsResponse {
    const parsed: unknown = JSON.parse(payload);
    if (
        typeof parsed !== 'object' ||
        parsed === null ||
        !('opzioniViaggio' in parsed) ||
        !Array.isArray(parsed.opzioniViaggio)
    ) {
        throw new Error('Il risultato SSE non rispetta il formato atteso.');
    }

    return parsed as TravelOptionsResponse;
}
