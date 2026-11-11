import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';

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
});
