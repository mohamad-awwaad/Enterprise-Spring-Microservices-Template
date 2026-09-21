import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom, catchError, of } from 'rxjs';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { environment } from '../../../environments/environment';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authService: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('adds withCredentials and the X-Requested-With header to every request', () => {
    http.get(`${environment.bffUrl}/api/orders`).subscribe({ error: () => {} });

    const req = httpMock.expectOne(`${environment.bffUrl}/api/orders`);
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    req.flush({}, { status: 200, statusText: 'OK' });
  });

  it('does not redirect to Keycloak on a 401 from the auth probe (/bff/user)', async () => {
    const clearAuthSpy = vi.spyOn(authService, 'clearAuth');
    const loginSpy = vi.spyOn(authService, 'login').mockImplementation(() => {});

    const result$ = http.get(`${environment.bffUrl}/user`).pipe(catchError(err => of(err)));
    const resultPromise = firstValueFrom(result$);

    httpMock.expectOne(`${environment.bffUrl}/user`).flush('unauthorized', {
      status: 401,
      statusText: 'Unauthorized'
    });
    await resultPromise;

    expect(clearAuthSpy).toHaveBeenCalled();
    expect(loginSpy).not.toHaveBeenCalled();
  });

  it('redirects to Keycloak on a 401 from any other endpoint', async () => {
    const clearAuthSpy = vi.spyOn(authService, 'clearAuth');
    const loginSpy = vi.spyOn(authService, 'login').mockImplementation(() => {});

    const result$ = http.get(`${environment.bffUrl}/api/orders`).pipe(catchError(err => of(err)));
    const resultPromise = firstValueFrom(result$);

    httpMock.expectOne(`${environment.bffUrl}/api/orders`).flush('unauthorized', {
      status: 401,
      statusText: 'Unauthorized'
    });
    await resultPromise;

    expect(clearAuthSpy).toHaveBeenCalled();
    expect(loginSpy).toHaveBeenCalledTimes(1);
  });

  it('does not touch AuthService on a non-401 error', async () => {
    const clearAuthSpy = vi.spyOn(authService, 'clearAuth');
    const loginSpy = vi.spyOn(authService, 'login').mockImplementation(() => {});

    const result$ = http.get(`${environment.bffUrl}/api/orders`).pipe(catchError(err => of(err)));
    const resultPromise = firstValueFrom(result$);

    httpMock.expectOne(`${environment.bffUrl}/api/orders`).flush('boom', {
      status: 500,
      statusText: 'Internal Server Error'
    });
    await resultPromise;

    expect(clearAuthSpy).not.toHaveBeenCalled();
    expect(loginSpy).not.toHaveBeenCalled();
  });
});
