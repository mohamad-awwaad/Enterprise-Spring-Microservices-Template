import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/services/auth.service';
import { ProfileService } from '../profile/profile.service';
import { OrdersService } from '../orders/orders.service';

@Component({
    selector: 'app-dashboard',
    imports: [
    RouterModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule
],
    template: `
    <h1>Dashboard</h1>

    @if (authService.currentUser(); as user) {
      <mat-card class="welcome-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>account_circle</mat-icon>
          <mat-card-title>Welcome, {{ user.name || user.preferred_username }}</mat-card-title>
          <mat-card-subtitle>{{ user.email }}</mat-card-subtitle>
        </mat-card-header>
      </mat-card>
    }

    <div class="card-grid">
      <mat-card>
        <mat-card-header>
          <mat-icon mat-card-avatar>person</mat-icon>
          <mat-card-title>Profile</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          @if (hasProfile()) {
            <p>Profile is configured</p>
          } @else {
            <p>No profile found. Create one to get started.</p>
          }
        </mat-card-content>
        <mat-card-actions>
          <button mat-button color="primary" routerLink="/profile">
            @if (hasProfile()) {
              View Profile
            } @else {
              Create Profile
            }
          </button>
        </mat-card-actions>
      </mat-card>

      <mat-card>
        <mat-card-header>
          <mat-icon mat-card-avatar>shopping_cart</mat-icon>
          <mat-card-title>Orders</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p>{{ orderCount() }} orders found</p>
        </mat-card-content>
        <mat-card-actions>
          <button mat-button color="primary" routerLink="/orders">View Orders</button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
    h1 {
      margin-bottom: 24px;
    }

    .welcome-card {
      margin-bottom: 24px;
    }

    .welcome-card mat-icon {
      font-size: 40px;
      height: 40px;
      width: 40px;
    }

    mat-card-content {
      padding-top: 16px;
    }
  `]
})
export class DashboardComponent implements OnInit {
  authService = inject(AuthService);
  private profileService = inject(ProfileService);
  private ordersService = inject(OrdersService);

  hasProfile = signal(false);
  orderCount = signal(0);

  async ngOnInit(): Promise<void> {
    this.loadStats();
  }

  private async loadStats(): Promise<void> {
    try {
      const profile = await this.profileService.getProfile();
      this.hasProfile.set(!!profile);
    } catch {
      this.hasProfile.set(false);
    }

    try {
      const orders = await this.ordersService.getOrders();
      this.orderCount.set(orders.totalElements);
    } catch {
      this.orderCount.set(0);
    }
  }
}
