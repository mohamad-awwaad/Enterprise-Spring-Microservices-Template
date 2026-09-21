import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ProfileService } from './profile.service';
import { UserProfile, Gender } from './profile.model';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmDialogComponent } from './confirm-dialog.component';

@Component({
    selector: 'app-profile',
    imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDialogModule
],
    template: `
    <h1>Profile</h1>

    @if (loading()) {
      <div class="loading-container">
        <mat-spinner diameter="40"></mat-spinner>
      </div>
    } @else if (profile()) {
      <mat-card>
        <mat-card-header>
          <mat-icon mat-card-avatar>person</mat-icon>
          <mat-card-title>{{ profile()!.firstName }} {{ profile()!.lastName }}</mat-card-title>
          <mat-card-subtitle>{{ profile()!.email }}</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <div class="profile-details">
            <p><strong>User ID:</strong> {{ profile()!.userId }}</p>
            <p><strong>Gender:</strong> {{ profile()!.gender }}</p>
            <p><strong>Age:</strong> {{ profile()!.age }}</p>
          </div>
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="warn" (click)="confirmDelete()">
            <mat-icon>delete</mat-icon>
            Delete Profile
          </button>
        </mat-card-actions>
      </mat-card>
    } @else {
      <mat-card>
        <mat-card-header>
          <mat-card-title>Create Profile</mat-card-title>
          <mat-card-subtitle>Fill in your details to create a profile</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <form class="form-container" (ngSubmit)="createProfile()">
            <mat-form-field appearance="outline">
              <mat-label>First Name</mat-label>
              <input matInput [(ngModel)]="newProfile.firstName" name="firstName" required>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Last Name</mat-label>
              <input matInput [(ngModel)]="newProfile.lastName" name="lastName" required>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Email</mat-label>
              <input matInput type="email" [(ngModel)]="newProfile.email" name="email" required>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Gender</mat-label>
              <mat-select [(ngModel)]="newProfile.gender" name="gender" required>
                <mat-option value="MALE">Male</mat-option>
                <mat-option value="FEMALE">Female</mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Age</mat-label>
              <input matInput type="number" [(ngModel)]="newProfile.age" name="age" required min="1" max="150">
            </mat-form-field>

            <div class="action-buttons">
              <button mat-raised-button color="primary" type="submit" [disabled]="saving()">
                @if (saving()) {
                  <mat-spinner diameter="20"></mat-spinner>
                } @else {
                  <mat-icon>save</mat-icon>
                  Create Profile
                }
              </button>
            </div>
          </form>
        </mat-card-content>
      </mat-card>
    }
  `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
    .loading-container {
      display: flex;
      justify-content: center;
      padding: 48px;
    }

    .profile-details {
      margin-top: 16px;
    }

    .profile-details p {
      margin: 8px 0;
    }

    mat-card-content {
      padding-top: 16px;
    }
  `]
})
export class ProfileComponent implements OnInit {
  private profileService = inject(ProfileService);
  private authService = inject(AuthService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  loading = signal(true);
  saving = signal(false);
  profile = signal<UserProfile | null>(null);

  newProfile: Omit<UserProfile, 'id'> = {
    userId: '',
    firstName: '',
    lastName: '',
    email: '',
    gender: 'MALE',
    age: 25
  };

  async ngOnInit(): Promise<void> {
    await this.loadProfile();
    this.prefillFromAuth();
  }

  private async loadProfile(): Promise<void> {
    this.loading.set(true);
    try {
      const profile = await this.profileService.getProfile();
      this.profile.set(profile);
    } catch (error) {
      this.snackBar.open('Failed to load profile', 'Dismiss', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  private prefillFromAuth(): void {
    const user = this.authService.currentUser();
    if (user) {
      this.newProfile.userId = user.sub;
      this.newProfile.email = user.email || '';
      if (user.name) {
        const parts = user.name.split(' ');
        this.newProfile.firstName = parts[0] || '';
        this.newProfile.lastName = parts.slice(1).join(' ') || '';
      }
    }
  }

  async createProfile(): Promise<void> {
    this.saving.set(true);
    try {
      const created = await this.profileService.createProfile(this.newProfile);
      this.profile.set(created);
      this.snackBar.open('Profile created successfully', 'Dismiss', { duration: 3000 });
    } catch (error) {
      this.snackBar.open('Failed to create profile', 'Dismiss', { duration: 3000 });
    } finally {
      this.saving.set(false);
    }
  }

  confirmDelete(): void {
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Delete Profile',
        message: 'Are you sure you want to delete your profile? This action cannot be undone.'
      }
    });

    dialogRef.afterClosed().subscribe(async (confirmed) => {
      if (confirmed) {
        await this.deleteProfile();
      }
    });
  }

  private async deleteProfile(): Promise<void> {
    try {
      await this.profileService.deleteProfile();
      this.profile.set(null);
      this.prefillFromAuth();
      this.snackBar.open('Profile deleted successfully', 'Dismiss', { duration: 3000 });
    } catch (error) {
      this.snackBar.open('Failed to delete profile', 'Dismiss', { duration: 3000 });
    }
  }
}
