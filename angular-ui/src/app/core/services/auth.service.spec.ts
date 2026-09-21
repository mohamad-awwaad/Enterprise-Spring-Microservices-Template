import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AuthService, User } from './auth.service';
import { environment } from '../../../environments/environment';

const USER: User = {
  sub: 'user-1',
  preferred_username: 'user',
  email: 'user@example.com',
  name: 'Test User'
};

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    document.body.innerHTML = '';
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  describe('checkAuth', () => {
    it('sets the current user and returns true on a successful probe', async () => {
      const result = service.checkAuth();

      const req = httpMock.expectOne(`${environment.bffUrl}/user`);
      expect(req.request.method).toBe('GET');
      req.flush(USER);

      expect(await result).toBe(true);
      expect(service.currentUser()).toEqual(USER);
      expect(service.isAuthenticated()).toBe(true);
    });

    it('maps a 401 to a logged-out state and returns false', async () => {
      const result = service.checkAuth();

      const req = httpMock.expectOne(`${environment.bffUrl}/user`);
      req.flush('unauthenticated', { status: 401, statusText: 'Unauthorized' });

      expect(await result).toBe(false);
      expect(service.currentUser()).toBeNull();
      expect(service.isAuthenticated()).toBe(false);
    });
  });

  describe('login', () => {
    it('redirects the browser to /bff/login', () => {
      const originalLocation = window.location;
      Object.defineProperty(window, 'location', {
        value: { ...originalLocation, href: '' },
        writable: true,
        configurable: true
      });

      service.login();

      expect(window.location.href).toBe(`${environment.bffUrl}/login`);

      Object.defineProperty(window, 'location', {
        value: originalLocation,
        writable: true,
        configurable: true
      });
    });
  });

  describe('logout', () => {
    it('submits a hidden POST form to /bff/logout carrying the _csrf field from the XSRF-TOKEN cookie', async () => {
      document.cookie = 'XSRF-TOKEN=the-csrf-token';
      const submitSpy = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

      await service.logout();

      const form = document.body.querySelector('form');
      expect(form).not.toBeNull();
      expect(form!.method.toUpperCase()).toBe('POST');
      expect(form!.action).toContain(`${environment.bffUrl}/logout`);

      const csrfInput = form!.querySelector<HTMLInputElement>('input[name="_csrf"]');
      expect(csrfInput).not.toBeNull();
      expect(csrfInput!.value).toBe('the-csrf-token');
      expect(submitSpy).toHaveBeenCalledTimes(1);
    });

    it('submits the form without a _csrf field when no XSRF-TOKEN cookie is present', async () => {
      vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

      await service.logout();

      const form = document.body.querySelector('form');
      expect(form).not.toBeNull();
      expect(form!.querySelector('input[name="_csrf"]')).toBeNull();
    });
  });

  describe('clearAuth', () => {
    it('clears the current user', async () => {
      const result = service.checkAuth();
      httpMock.expectOne(`${environment.bffUrl}/user`).flush(USER);
      await result;
      expect(service.isAuthenticated()).toBe(true);

      service.clearAuth();

      expect(service.currentUser()).toBeNull();
      expect(service.isAuthenticated()).toBe(false);
    });
  });
});
