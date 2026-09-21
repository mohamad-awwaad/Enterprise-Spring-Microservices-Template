import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ConfirmComponent } from './confirm.component';
import { environment } from '../../../environments/environment';

function activatedRouteWithToken(token: string | null) {
  return {
    snapshot: {
      queryParamMap: convertToParamMap(token ? { token } : {})
    }
  };
}

describe('ConfirmComponent', () => {
  let httpMock: HttpTestingController;

  function createFixture(token: string | null): ComponentFixture<ConfirmComponent> {
    TestBed.configureTestingModule({
      imports: [ConfirmComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: activatedRouteWithToken(token) }
      ]
    });
    httpMock = TestBed.inject(HttpTestingController);
    return TestBed.createComponent(ConfirmComponent);
  }

  afterEach(() => {
    httpMock.verify();
  });

  it('shows a success message once the confirmation call succeeds', async () => {
    const fixture = createFixture('the-token');
    fixture.detectChanges();

    const req = httpMock.expectOne(
      req => req.url === `${environment.bffUrl}/public/profile/confirm`
    );
    expect(req.request.params.get('token')).toBe('the-token');
    req.flush({ message: 'confirmed', email: 'user@example.com' });

    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();

    expect(fixture.componentInstance.success()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Email Confirmed');
  });

  it('shows an error message when the confirmation call fails', async () => {
    const fixture = createFixture('expired-token');
    fixture.detectChanges();

    const req = httpMock.expectOne(
      req => req.url === `${environment.bffUrl}/public/profile/confirm`
    );
    req.flush(
      { message: 'The link may have expired.' },
      { status: 400, statusText: 'Bad Request' }
    );

    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();

    expect(fixture.componentInstance.success()).toBe(false);
    expect(fixture.componentInstance.errorMessage()).toBe('The link may have expired.');
    expect(fixture.nativeElement.textContent).toContain('Confirmation Failed');
  });

  it('shows an error immediately when there is no token in the URL', async () => {
    const fixture = createFixture(null);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.loading()).toBe(false);
    expect(fixture.componentInstance.errorMessage()).toContain('Invalid confirmation link');
    httpMock.expectNone(`${environment.bffUrl}/public/profile/confirm`);
  });
});
