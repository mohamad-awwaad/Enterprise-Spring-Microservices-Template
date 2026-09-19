import { Component, inject, signal } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { RegisterService, RegisterRequest } from './register.service';

@Component({
    selector: 'app-register',
    imports: [
    FormsModule,
    RouterModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
],
    template: `
    <div class="register-container">
      <mat-card class="register-card">
        <mat-card-header>
          <mat-card-title>Create Account</mat-card-title>
          <mat-card-subtitle>Fill in your details to register</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          @if (success()) {
            <div class="success-message">
              <mat-icon color="primary">check_circle</mat-icon>
              <h3>Registration Successful!</h3>
              <p>Please check your email to confirm your registration.</p>
              <p class="email-hint">A confirmation link has been sent to <strong>{{ registeredEmail() }}</strong></p>
              <button mat-raised-button color="primary" routerLink="/login">
                Back to Login
              </button>
            </div>
          } @else {
            <form class="form-container" (ngSubmit)="register()">
              <div class="form-row">
                <mat-form-field appearance="outline">
                  <mat-label>First Name</mat-label>
                  <input matInput [(ngModel)]="formData.firstName" name="firstName" required>
                </mat-form-field>

                <mat-form-field appearance="outline">
                  <mat-label>Last Name</mat-label>
                  <input matInput [(ngModel)]="formData.lastName" name="lastName" required>
                </mat-form-field>
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Email</mat-label>
                <input matInput type="email" [(ngModel)]="formData.email" name="email" required>
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Mobile Number (Optional)</mat-label>
                <input matInput [(ngModel)]="formData.mobileNumber" name="mobileNumber" placeholder="+1234567890">
              </mat-form-field>

              <div class="form-row">
                <mat-form-field appearance="outline">
                  <mat-label>Gender</mat-label>
                  <mat-select [(ngModel)]="formData.gender" name="gender" required>
                    <mat-option value="MALE">Male</mat-option>
                    <mat-option value="FEMALE">Female</mat-option>
                  </mat-select>
                </mat-form-field>

                <mat-form-field appearance="outline">
                  <mat-label>Age</mat-label>
                  <input matInput type="number" [(ngModel)]="formData.age" name="age" required min="1" max="150">
                </mat-form-field>
              </div>

              <div class="action-buttons">
                <button mat-raised-button color="primary" type="submit" [disabled]="submitting()">
                  @if (submitting()) {
                    <mat-spinner diameter="20"></mat-spinner>
                  } @else {
                    <mat-icon>person_add</mat-icon>
                    Register
                  }
                </button>
              </div>

              <div class="login-link">
                Already have an account? <a routerLink="/login">Sign in</a>
              </div>
            </form>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
    styles: [`
    .register-container {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      background-color: #f5f5f5;
      padding: 16px;
    }

    .register-card {
      max-width: 500px;
      width: 100%;
    }

    mat-card-header {
      margin-bottom: 16px;
    }

    .form-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .form-row {
      display: flex;
      gap: 16px;
    }

    .form-row mat-form-field {
      flex: 1;
    }

    .full-width {
      width: 100%;
    }

    .action-buttons {
      margin-top: 16px;
    }

    .action-buttons button {
      width: 100%;
    }

    .login-link {
      text-align: center;
      margin-top: 16px;
      color: #666;
    }

    .login-link a {
      color: #1976d2;
      text-decoration: none;
    }

    .success-message {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 24px;
      text-align: center;
    }

    .success-message mat-icon {
      font-size: 64px;
      height: 64px;
      width: 64px;
      margin-bottom: 16px;
    }

    .email-hint {
      color: #666;
      margin-bottom: 24px;
    }
  `]
})
export class RegisterComponent {
  private registerService = inject(RegisterService);
  private snackBar = inject(MatSnackBar);

  submitting = signal(false);
  success = signal(false);
  registeredEmail = signal('');

  formData: RegisterRequest = {
    email: '',
    firstName: '',
    lastName: '',
    mobileNumber: '',
    gender: 'MALE',
    age: 25
  };

  async register(): Promise<void> {
    this.submitting.set(true);
    try {
      const response = await this.registerService.register(this.formData);
      this.registeredEmail.set(response.email);
      this.success.set(true);
    } catch (error: any) {
      const message = error?.error?.message || 'Registration failed. Please try again.';
      this.snackBar.open(message, 'Dismiss', { duration: 5000 });
    } finally {
      this.submitting.set(false);
    }
  }
}
