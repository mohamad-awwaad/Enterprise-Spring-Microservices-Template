import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { RegisterComponent } from './register.component';
import { environment } from '../../../environments/environment';

describe('RegisterComponent', () => {
  let fixture: ComponentFixture<RegisterComponent>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations()
      ]
    });
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RegisterComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('submits the registration form and shows the confirmation message', async () => {
    fixture.componentInstance.formData = {
      email: 'new.user@example.com',
      firstName: 'New',
      lastName: 'User',
      mobileNumber: '',
      gender: 'MALE',
      age: 28
    };

    const registerPromise = fixture.componentInstance.register();

    const req = httpMock.expectOne(`${environment.bffUrl}/public/profile/register`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.email).toBe('new.user@example.com');
    req.flush({ message: 'ok', email: 'new.user@example.com' });

    await registerPromise;
    fixture.detectChanges();

    expect(fixture.componentInstance.success()).toBe(true);
    expect(fixture.componentInstance.registeredEmail()).toBe('new.user@example.com');
    expect(fixture.nativeElement.textContent).toContain('Registration Successful');
  });

  it('surfaces the backend error message and stays on the form when registration fails', async () => {
    fixture.componentInstance.formData = {
      email: 'taken@example.com',
      firstName: 'New',
      lastName: 'User',
      mobileNumber: '',
      gender: 'MALE',
      age: 28
    };

    const registerPromise = fixture.componentInstance.register();

    const req = httpMock.expectOne(`${environment.bffUrl}/public/profile/register`);
    req.flush(
      { message: 'Email already registered' },
      { status: 409, statusText: 'Conflict' }
    );

    await registerPromise;
    fixture.detectChanges();

    expect(fixture.componentInstance.success()).toBe(false);
    expect(fixture.componentInstance.submitting()).toBe(false);
  });
});
