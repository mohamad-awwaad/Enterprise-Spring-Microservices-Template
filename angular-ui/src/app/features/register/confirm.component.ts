import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RegisterService } from './register.service';

@Component({
    selector: 'app-confirm',
    imports: [
    RouterModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule
],
    template: `
    <div class="confirm-container">
      <mat-card class="confirm-card">
        @if (loading()) {
          <mat-card-content>
            <div class="status-message">
              <mat-spinner diameter="48"></mat-spinner>
              <h3>Confirming your email...</h3>
            </div>
          </mat-card-content>
        } @else if (success()) {
          <mat-card-content>
            <div class="status-message success">
              <mat-icon>check_circle</mat-icon>
              <h3>Email Confirmed!</h3>
              <p>Your email has been verified successfully.</p>
              <p class="hint">Please check your email for a link to set your password.</p>
              <button mat-raised-button color="primary" routerLink="/login">
                Go to Login
              </button>
            </div>
          </mat-card-content>
        } @else {
          <mat-card-content>
            <div class="status-message error">
              <mat-icon>error</mat-icon>
              <h3>Confirmation Failed</h3>
              <p>{{ errorMessage() }}</p>
              <button mat-raised-button color="primary" routerLink="/register">
                Try Again
              </button>
            </div>
          </mat-card-content>
        }
      </mat-card>
    </div>
  `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
    .confirm-container {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      background-color: #f5f5f5;
    }

    .confirm-card {
      max-width: 400px;
      width: 100%;
      margin: 16px;
    }

    .status-message {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 32px;
      text-align: center;
    }

    .status-message mat-icon {
      font-size: 64px;
      height: 64px;
      width: 64px;
      margin-bottom: 16px;
    }

    .status-message.success mat-icon {
      color: #4caf50;
    }

    .status-message.error mat-icon {
      color: #f44336;
    }

    .hint {
      color: #666;
      margin-bottom: 24px;
    }

    h3 {
      margin-bottom: 8px;
    }

    p {
      margin-bottom: 16px;
    }
  `]
})
export class ConfirmComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private registerService = inject(RegisterService);

  loading = signal(true);
  success = signal(false);
  errorMessage = signal('');

  async ngOnInit(): Promise<void> {
    const token = this.route.snapshot.queryParamMap.get('token');

    if (!token) {
      this.loading.set(false);
      this.errorMessage.set('Invalid confirmation link. No token provided.');
      return;
    }

    try {
      await this.registerService.confirm(token);
      this.success.set(true);
    } catch (error: any) {
      this.errorMessage.set(
        error?.error?.message || 'Failed to confirm email. The link may have expired.'
      );
    } finally {
      this.loading.set(false);
    }
  }
}
