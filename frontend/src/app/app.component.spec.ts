import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';

registerLocaleData(localeIt);

describe('AppComponent', () => {
    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [AppComponent],
            providers: [provideHttpClient(), provideHttpClientTesting()],
        }).compileComponents();
    });

    it('creates the travel search form', () => {
        const fixture = TestBed.createComponent(AppComponent);
        fixture.detectChanges();

        expect(fixture.componentInstance).toBeTruthy();
        expect(fixture.nativeElement.querySelector('form')).toBeTruthy();
    });

    it('shows the Niente Panico! title and subtitle', () => {
        const fixture = TestBed.createComponent(AppComponent);
        fixture.detectChanges();

        const page: HTMLElement = fixture.nativeElement;
        expect(page.querySelector('h1')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Niente Panico!');
        expect(page.querySelector('.subtitle')?.textContent?.trim())
            .toBe('Viaggia per meno di 30 dollari altariani al giorno ( più o meno )');
    });

    it('shows transport icons based on the travel mode, not the option order', () => {
        const fixture = TestBed.createComponent(AppComponent);
        fixture.componentInstance.alternatives = [
            {
                mezzo: 'Autobus',
                costoTotalizzato: 40,
                tempoPercorrenza: '5h',
                descrizioneDettagliata: 'Roma-Milano',
            },
            {
                mezzo: 'Aereo',
                costoTotalizzato: 150,
                tempoPercorrenza: '1h',
                descrizioneDettagliata: 'Roma-Milano',
            },
            {
                mezzo: 'Treno',
                costoTotalizzato: 100,
                tempoPercorrenza: '3h',
                descrizioneDettagliata: 'Roma-Milano',
            },
        ];
        fixture.detectChanges();

        const page: HTMLElement = fixture.nativeElement;
        const icons = Array.from(page.querySelectorAll('.option-icon'))
            .map((icon) => icon.textContent?.trim());
        expect(icons).toEqual(['🚗', '✈', '🚆']);
    });

    it('keeps the departure date optional and displays unavailable details for legacy proposals', () => {
        const fixture = TestBed.createComponent(AppComponent);
        fixture.componentInstance.alternatives = [{
            mezzo: 'Treno',
            costoTotalizzato: 100,
            tempoPercorrenza: '3h',
            descrizioneDettagliata: 'Roma-Milano',
        }];
        fixture.detectChanges();

        const page: HTMLElement = fixture.nativeElement;
        expect(fixture.componentInstance.form.controls.dataPartenza.valid).toBe(true);
        expect(page.querySelector('.details-unavailable')?.textContent?.trim())
            .toBe('Dettagli indicativi: Dato non disponibile.');
    });

    it('renders sourced itinerary details for a proposal', () => {
        const fixture = TestBed.createComponent(AppComponent);
        fixture.componentInstance.alternatives = [{
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
        }];
        fixture.detectChanges();

        const page: HTMLElement = fixture.nativeElement;
        expect(page.textContent).toContain('DETTAGLI INDICATIVI');
        expect(page.textContent).toContain('Frecciarossa');
        expect(page.querySelector('a[href="https://example.test/itinerary"]')?.textContent)
            .toBe('Fonte 1');
    });
});
