import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';

import { LoginComponent } from './login.component';
import { AuthService, User } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

const USER: User = {
  sub: 'user-1',
  preferred_username: 'user',
  email: 'user@example.com',
  name: 'Test User'
};

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('shows a spinner while checking auth, then the sign-in button once logged out is confirmed', async () => {
    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.checking()).toBe(true);
    let button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);

    httpMock.expectOne(`${environment.bffUrl}/user`).flush('unauthenticated', {
      status: 401,
      statusText: 'Unauthorized'
    });
    await vi.waitFor(() => expect(fixture.componentInstance.checking()).toBe(false));
    fixture.detectChanges();

    expect(fixture.componentInstance.checking()).toBe(false);
    button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Please sign in to continue');
    expect(fixture.nativeElement.textContent).not.toContain('Checking authentication');
  });

  it('navigates straight to /dashboard when a session already exists', async () => {
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.bffUrl}/user`).flush(USER);

    await vi.waitFor(() => expect(navigateSpy).toHaveBeenCalledWith(['/dashboard']));
  });

  it('delegates to AuthService.login() when the sign-in button is used', () => {
    fixture = TestBed.createComponent(LoginComponent);
    const authService = TestBed.inject(AuthService);
    const loginSpy = vi.spyOn(authService, 'login').mockImplementation(() => {});

    fixture.componentInstance.login();

    expect(loginSpy).toHaveBeenCalledTimes(1);
  });
});
