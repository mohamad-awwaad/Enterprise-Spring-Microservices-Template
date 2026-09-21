import { Component, inject, OnInit, ChangeDetectionStrategy, signal } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/services/auth.service';

@Component({
    selector: 'app-login',
    imports: [
        RouterModule,
        MatCardModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule
    ],
    template: `
    <div class="login-container">
      <mat-card class="login-card">
        <mat-card-header>
          <mat-card-title>SEC Microservice</mat-card-title>
          <mat-card-subtitle>Secure Microservices Template</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          @if (checking()) {
            <div class="checking-auth">
              <mat-spinner diameter="40"></mat-spinner>
              <p>Checking authentication...</p>
            </div>
          } @else {
            <p>Please sign in to continue</p>
          }
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="primary" (click)="login()" [disabled]="checking()">
            <mat-icon>login</mat-icon>
            Sign in with Keycloak
          </button>
          <div class="register-link">
            Don't have an account? <a routerLink="/register">Register</a>
          </div>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
    .login-container {
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100vh;
      background-color: #f5f5f5;
    }

    .login-card {
      max-width: 400px;
      width: 100%;
      margin: 16px;
    }

    mat-card-header {
      margin-bottom: 16px;
    }

    mat-card-actions {
      padding: 16px;
    }

    mat-card-actions button {
      width: 100%;
    }

    .checking-auth {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;
      padding: 24px 0;
    }

    .register-link {
      text-align: center;
      margin-top: 16px;
      color: #666;
    }

    .register-link a {
      color: #1976d2;
      text-decoration: none;
    }
  `]
})
export class LoginComponent implements OnInit {
  private authService = inject(AuthService);
  private router = inject(Router);

  checking = signal(true);

  async ngOnInit(): Promise<void> {
    const isAuth = await this.authService.checkAuth();
    if (isAuth) {
      this.router.navigate(['/dashboard']);
    } else {
      this.checking.set(false);
    }
  }

  login(): void {
    this.authService.login();
  }
}
