import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { environment } from '../../../environments/environment';

// AuthService.checkAuth() probes this endpoint to find out whether a session exists, and it is
// EXPECTED to 401 when the user isn't logged in (e.g. every time /login itself loads, and right
// after logout). Redirecting to Keycloak on that 401 would bounce the login page straight back
// to Keycloak before "Sign in with Keycloak" / the registration link ever get a chance to
// render, so this one request must not trigger the redirect below.
const AUTH_PROBE_URL = `${environment.bffUrl}/user`;

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const authService = inject(AuthService);

  const authReq = req.clone({
    withCredentials: true,
    setHeaders: {
      'X-Requested-With': 'XMLHttpRequest'
    }
  });

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        authService.clearAuth();
        // AuthService.checkAuth() / authGuard already route to /login on a failed probe;
        // redirecting to Keycloak here too would fight with that.
        if (req.url !== AUTH_PROBE_URL) {
          authService.login();
        }
      }
      return throwError(() => error);
    })
  );
};
