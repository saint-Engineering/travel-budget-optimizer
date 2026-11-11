import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
    parseTravelOptionsResponse,
    TravelService,
} from './travel.service';

describe('TravelService', () => {
    let service: TravelService;
    let httpTesting: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(TravelService);
        httpTesting = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpTesting.verify());

    it('sends an optional departure date when supplied', () => {
        service.avviaRichiestaOttimizzazione('Roma', 'Milano', 300, '2026-12-01').subscribe();

        const request = httpTesting.expectOne('/api/viaggi/ottimizza');
        expect(request.request.body).toEqual({
            partenza: 'Roma',
            destinazione: 'Milano',
            budgetMassimo: 300,
            dataPartenza: '2026-12-01',
        });
        request.flush({ jobId: 'job-1' });
    });

    it('omits the departure date when not supplied', () => {
        service.avviaRichiestaOttimizzazione('Roma', 'Milano', 300).subscribe();

        const request = httpTesting.expectOne('/api/viaggi/ottimizza');
        expect(request.request.body).toEqual({
            partenza: 'Roma',
            destinazione: 'Milano',
            budgetMassimo: 300,
        });
        request.flush({ jobId: 'job-2' });
    });

    it('accepts legacy result payloads without itinerary details', () => {
        const response = parseTravelOptionsResponse(JSON.stringify({
            opzioniViaggio: [{
                mezzo: 'Treno',
                costoTotalizzato: 100,
                tempoPercorrenza: '3h',
                descrizioneDettagliata: 'Roma-Milano',
            }],
        }));

        expect(response.opzioniViaggio[0].dettaglioViaggio).toBeUndefined();
    });

    it('parses structured itinerary details', () => {
        const response = parseTravelOptionsResponse(JSON.stringify({
            opzioniViaggio: [{
                mezzo: 'Treno',
                costoTotalizzato: 100,
                tempoPercorrenza: '3h',
                descrizioneDettagliata: 'Roma-Milano',
                dettaglioViaggio: {
                    tratte: [{
                        partenza: 'Roma Termini',
                        destinazione: 'Milano Centrale',
                        orarioPartenza: '09:00',
                        orarioArrivo: '12:00',
                        vettore: 'Frecciarossa',
                        fermate: ['Firenze SMN'],
                    }],
                    fonti: ['https://example.test/itinerary'],
                    indicativo: true,
                },
            }],
        }));

        expect(response.opzioniViaggio[0].dettaglioViaggio?.tratte[0].vettore).toBe('Frecciarossa');
        expect(response.opzioniViaggio[0].dettaglioViaggio?.indicativo).toBe(true);
    });

    it('treats omitted itinerary fields as unavailable', () => {
        const response = parseTravelOptionsResponse(JSON.stringify({
            opzioniViaggio: [{
                mezzo: 'Autobus',
                costoTotalizzato: 40,
                tempoPercorrenza: '5h',
                descrizioneDettagliata: 'Roma-Milano',
                dettaglioViaggio: {
                    tratte: [{ fermate: ['Firenze'] }],
                    fonti: ['https://example.test/itinerary'],
                    indicativo: true,
                },
            }],
        }));

        expect(response.opzioniViaggio[0].dettaglioViaggio?.tratte[0].partenza).toBeNull();
        expect(response.opzioniViaggio[0].dettaglioViaggio?.tratte[0].fermate).toEqual(['Firenze']);
    });
});
