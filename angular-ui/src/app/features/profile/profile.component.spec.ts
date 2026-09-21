import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';

import { ProfileComponent } from './profile.component';
import { UserProfile } from './profile.model';
import { AuthService, User } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

const USER: User = {
  sub: 'user-1',
  preferred_username: 'user',
  email: 'user@example.com',
  name: 'Ada Lovelace'
};

const PROFILE: UserProfile = {
  id: 1,
  userId: 'user-1',
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'user@example.com',
  gender: 'FEMALE',
  age: 30
};

describe('ProfileComponent', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations()
      ]
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('renders the existing profile when one is found', async () => {
    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.bffUrl}/api/profile`).flush(PROFILE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();

    expect(fixture.componentInstance.profile()).toEqual(PROFILE);
    expect(fixture.nativeElement.textContent).toContain('Ada Lovelace');
  });

  it('shows the create form and prefills it from the signed-in user when no profile exists', async () => {
    const authService = TestBed.inject(AuthService);
    const authPromise = authService.checkAuth();
    httpMock.expectOne(`${environment.bffUrl}/user`).flush(USER);
    await authPromise;

    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.bffUrl}/api/profile`).flush('not found', {
      status: 404,
      statusText: 'Not Found'
    });
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();

    expect(fixture.componentInstance.profile()).toBeNull();
    expect(fixture.componentInstance.newProfile.firstName).toBe('Ada');
    expect(fixture.componentInstance.newProfile.lastName).toBe('Lovelace');
    expect(fixture.componentInstance.newProfile.email).toBe('user@example.com');
    expect(fixture.nativeElement.textContent).toContain('Create Profile');
  });

  it('creates a profile and displays it', async () => {
    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.bffUrl}/api/profile`).flush('not found', {
      status: 404,
      statusText: 'Not Found'
    });
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));

    fixture.componentInstance.newProfile = {
      userId: 'user-1',
      firstName: 'Ada',
      lastName: 'Lovelace',
      email: 'user@example.com',
      gender: 'FEMALE',
      age: 30
    };
    const createPromise = fixture.componentInstance.createProfile();

    httpMock.expectOne(`${environment.bffUrl}/api/profile`).flush(PROFILE);
    await createPromise;
    fixture.detectChanges();

    expect(fixture.componentInstance.profile()).toEqual(PROFILE);
    expect(fixture.componentInstance.saving()).toBe(false);
  });

  it('deletes the profile after the confirmation dialog is accepted', async () => {
    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.bffUrl}/api/profile`).flush(PROFILE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    expect(fixture.componentInstance.profile()).toEqual(PROFILE);

    // ProfileComponent imports MatDialogModule directly, which (per Angular Material's
    // nested-dialog support) gives the component its own MatDialog instance distinct from
    // the root one TestBed.inject(MatDialog) would return - so the spy has to be installed
    // on the instance the component itself resolves via its own injector.
    const dialog = fixture.debugElement.injector.get(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of(true)
    } as MatDialogRef<unknown>);

    fixture.componentInstance.confirmDelete();

    const deleteReq = await vi.waitFor(() => httpMock.expectOne(`${environment.bffUrl}/api/profile`));
    deleteReq.flush(null);

    await vi.waitFor(() => expect(fixture.componentInstance.profile()).toBeNull());
  });

  it('leaves the profile untouched when the confirmation dialog is dismissed', async () => {
    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.bffUrl}/api/profile`).flush(PROFILE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));

    const dialog = fixture.debugElement.injector.get(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of(false)
    } as MatDialogRef<unknown>);

    fixture.componentInstance.confirmDelete();
    await new Promise(resolve => setTimeout(resolve, 0));

    httpMock.expectNone(`${environment.bffUrl}/api/profile`);
    expect(fixture.componentInstance.profile()).toEqual(PROFILE);
  });
});
