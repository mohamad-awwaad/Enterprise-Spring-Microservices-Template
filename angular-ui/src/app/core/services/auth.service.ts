import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface User {
  sub: string;
  preferred_username: string;
  email: string;
  name: string;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly currentUserSignal = signal<User | null>(null);

  readonly currentUser = this.currentUserSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUserSignal() !== null);

  constructor(
    private http: HttpClient,
    private router: Router
  ) {}

  async checkAuth(): Promise<boolean> {
    try {
      const user = await firstValueFrom(
        this.http.get<User>(`${environment.bffUrl}/user`)
      );
      this.currentUserSignal.set(user);
      return true;
    } catch {
      this.currentUserSignal.set(null);
      return false;
    }
  }

  login(): void {
    window.location.href = `${environment.bffUrl}/login`;
  }

  /**
   * Logs out via a real (top-level) form POST rather than a fetch/XHR or a plain
   * `window.location.href` navigation.
   * <p>
   * `/bff/logout` is now a POST endpoint (CSRF-protected, like any other state-changing BFF
   * request - see BffController#logout), so a plain navigation would just hit a 405, and an
   * `HttpClient` POST would follow the redirect chain internally as an XHR/fetch instead of
   * letting the browser actually navigate to Keycloak and back. Submitting a hidden form is the
   * standard way to get a real browser-driven POST + redirect-follow, and it lets us attach the
   * CSRF token as the `_csrf` form field that Spring's `CsrfTokenRequestAttributeHandler`
   * accepts (Angular's XSRF interceptor only ever attaches it as a header, which doesn't apply
   * to a plain form submission).
   */
  async logout(): Promise<void> {
    const csrfToken = this.getCookie('XSRF-TOKEN');

    const form = document.createElement('form');
    form.method = 'POST';
    form.action = `${environment.bffUrl}/logout`;

    if (csrfToken) {
      const csrfInput = document.createElement('input');
      csrfInput.type = 'hidden';
      csrfInput.name = '_csrf';
      csrfInput.value = csrfToken;
      form.appendChild(csrfInput);
    }

    document.body.appendChild(form);
    form.submit();
  }

  clearAuth(): void {
    this.currentUserSignal.set(null);
  }

  /** Reads and URL-decodes a cookie value by name, or null if it isn't set. */
  private getCookie(name: string): string | null {
    const match = document.cookie.match(new RegExp('(?:^|;\\s*)' + name + '=([^;]*)'));
    return match ? decodeURIComponent(match[1]) : null;
  }
}
