import { CommonModule, CurrencyPipe } from '@angular/common';
import { Component, OnDestroy, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
    TravelOption,
    TravelService,
    TravelSseEvent,
} from './services/travel.service';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [CommonModule, ReactiveFormsModule, CurrencyPipe],
    templateUrl: './app.component.html',
    styleUrl: './app.component.css',
})
export class AppComponent implements OnDestroy {
    private readonly formBuilder = inject(FormBuilder);
    private readonly travelService = inject(TravelService);
    private requestSubscription?: Subscription;
    private streamSubscription?: Subscription;

    readonly form = this.formBuilder.nonNullable.group({
        partenza: ['', [Validators.required, Validators.minLength(2)]],
        destinazione: ['', [Validators.required, Validators.minLength(2)]],
        budgetMassimo: [300, [Validators.required, Validators.min(0.01)]],
        dataPartenza: [''],
    });

    jobId: string | null = null;
    logs: string[] = [];
    alternatives: TravelOption[] = [];
    isLoading = false;
    progress = 0;
    errorMessage: string | null = null;

    get partenza() {
        return this.form.controls.partenza;
    }

    get destinazione() {
        return this.form.controls.destinazione;
    }

    get budgetMassimo() {
        return this.form.controls.budgetMassimo;
    }

    inviaRichiesta(): void {
        if (this.form.invalid || this.isLoading) {
            this.form.markAllAsTouched();
            return;
        }

        this.requestSubscription?.unsubscribe();
        this.streamSubscription?.unsubscribe();
        this.resetResults();
        this.isLoading = true;
        const { partenza, destinazione, budgetMassimo, dataPartenza } = this.form.getRawValue();

        this.requestSubscription = this.travelService
            .avviaRichiestaOttimizzazione(
                partenza.trim(),
                destinazione.trim(),
                budgetMassimo,
                dataPartenza || undefined,
            )
            .subscribe({
                next: ({ jobId }) => {
                    this.jobId = jobId;
                    this.logs = ['Richiesta accettata. Avvio dell’ottimizzazione…'];
                    this.ascoltaJob(jobId);
                },
                error: () => {
                    this.isLoading = false;
                    this.errorMessage = 'Non è stato possibile avviare la richiesta. Verifica che il backend sia raggiungibile e riprova.';
                },
            });
    }

    ngOnDestroy(): void {
        this.requestSubscription?.unsubscribe();
        this.streamSubscription?.unsubscribe();
    }

    private ascoltaJob(jobId: string): void {
        this.streamSubscription = this.travelService
            .ascoltaAggiornamentiStatus(jobId)
            .subscribe({
                next: (event) => this.gestisciEvento(event),
                error: () => {
                    this.isLoading = false;
                    this.errorMessage = 'La connessione agli aggiornamenti si è interrotta. Puoi inviare una nuova richiesta.';
                },
                complete: () => {
                    this.isLoading = false;
                },
            });
    }

    private gestisciEvento(event: TravelSseEvent): void {
        switch (event.type) {
            case 'status':
                this.logs = [...this.logs, event.message];
                this.progress = Math.max(this.progress, this.progressoDaStato(event.message));
                break;
            case 'result':
                this.alternatives = event.result.opzioniViaggio;
                this.progress = 100;
                this.logs = [...this.logs, 'Ottimizzazione completata. Ecco le alternative.'];
                break;
            case 'timeout':
                this.isLoading = false;
                this.errorMessage = event.message || 'La richiesta è scaduta. Riprova.';
                break;
        }
    }

    private progressoDaStato(stato: string): number {
        if (/fase\s*3/i.test(stato)) return 75;
        if (/fase\s*2/i.test(stato)) return 50;
        if (/fase\s*1/i.test(stato)) return 25;
        return this.progress;
    }

    private resetResults(): void {
        this.jobId = null;
        this.logs = [];
        this.alternatives = [];
        this.progress = 0;
        this.errorMessage = null;
    }
}
