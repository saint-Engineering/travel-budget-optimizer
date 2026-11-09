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
});
