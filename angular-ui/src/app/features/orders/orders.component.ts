import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { OrdersService } from './orders.service';
import { Order } from './order.model';

@Component({
    selector: 'app-orders',
    imports: [
        CommonModule,
        DatePipe,
        MatCardModule,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSnackBarModule,
        MatPaginatorModule
    ],
    template: `
    <div class="header">
      <h1>Orders</h1>
      <button mat-raised-button color="primary" (click)="createOrder()" [disabled]="creating()">
        @if (creating()) {
          <mat-spinner diameter="20"></mat-spinner>
        } @else {
          <mat-icon>add</mat-icon>
          Create Order
        }
      </button>
    </div>

    @if (loading()) {
      <div class="loading-container">
        <mat-spinner diameter="40"></mat-spinner>
      </div>
    } @else if (orders().length === 0) {
      <mat-card>
        <mat-card-content>
          <div class="empty-state">
            <mat-icon>shopping_cart</mat-icon>
            <p>No orders found</p>
            <p class="hint">Click "Create Order" to create your first order</p>
          </div>
        </mat-card-content>
      </mat-card>
    } @else {
      <mat-card>
        <table mat-table [dataSource]="orders()" class="full-width">
          <ng-container matColumnDef="orderNumber">
            <th mat-header-cell *matHeaderCellDef>Order Number</th>
            <td mat-cell *matCellDef="let order">{{ order.orderNumber }}</td>
          </ng-container>

          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let order">
              <span class="status-chip" [class]="'status-' + order.status.toLowerCase()">
                {{ order.status }}
              </span>
            </td>
          </ng-container>

          <ng-container matColumnDef="creationTime">
            <th mat-header-cell *matHeaderCellDef>Created</th>
            <td mat-cell *matCellDef="let order">{{ order.creationTime | date:'medium' }}</td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>
        
        <mat-paginator
          [length]="totalElements()"
          [pageSize]="pageSize()"
          [pageSizeOptions]="[5, 10, 20, 50]"
          [pageIndex]="pageIndex()"
          (page)="onPageChange($event)"
          showFirstLastButtons>
        </mat-paginator>
      </mat-card>
    }
  `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
    }

    .header h1 {
      margin: 0;
    }

    .loading-container {
      display: flex;
      justify-content: center;
      padding: 48px;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 48px;
      color: #666;
    }

    .empty-state mat-icon {
      font-size: 64px;
      height: 64px;
      width: 64px;
      margin-bottom: 16px;
    }

    .empty-state .hint {
      font-size: 14px;
      color: #999;
    }

    table {
      width: 100%;
    }
    
    .status-chip {
      padding: 4px 8px;
      border-radius: 16px;
      font-size: 12px;
      font-weight: 500;
    }
    
    .status-created { background-color: #e3f2fd; color: #1976d2; }
    .status-processing { background-color: #fff3e0; color: #f57c00; }
    .status-completed { background-color: #e8f5e9; color: #388e3c; }
    .status-cancelled { background-color: #ffebee; color: #d32f2f; }
  `]
})
export class OrdersComponent implements OnInit {
  private ordersService = inject(OrdersService);
  private snackBar = inject(MatSnackBar);

  loading = signal(true);
  creating = signal(false);
  orders = signal<Order[]>([]);
  
  // Pagination state
  totalElements = signal(0);
  pageSize = signal(20);
  pageIndex = signal(0);

  displayedColumns = ['orderNumber', 'status', 'creationTime'];

  async ngOnInit(): Promise<void> {
    await this.loadOrders();
  }

  private async loadOrders(): Promise<void> {
    this.loading.set(true);
    try {
      const page = await this.ordersService.getOrders(this.pageIndex(), this.pageSize());
      this.orders.set(page.content);
      this.totalElements.set(page.page.totalElements);
    } catch (error) {
      this.snackBar.open('Failed to load orders', 'Dismiss', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  async createOrder(): Promise<void> {
    this.creating.set(true);
    try {
      await this.ordersService.createOrder();
      this.snackBar.open(`Order created`, 'Dismiss', { duration: 3000 });
      // Reload current page to show new order (or reset to first page)
      this.pageIndex.set(0);
      await this.loadOrders();
    } catch (error) {
      this.snackBar.open('Failed to create order', 'Dismiss', { duration: 3000 });
    } finally {
      this.creating.set(false);
    }
  }
  
  async onPageChange(event: PageEvent): Promise<void> {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    await this.loadOrders();
  }
}
