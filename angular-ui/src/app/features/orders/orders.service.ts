import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Order } from './order.model';

// Matches Spring Data's PagedModel JSON shape, now returned explicitly by
// OrdersController#getOrders (new PagedModel<>(page)) instead of a bare Page:
// { content: [...], page: { size, number, totalElements, totalPages } }
export interface Page<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

@Injectable({
  providedIn: 'root'
})
export class OrdersService {
  private http = inject(HttpClient);
  private readonly apiUrl = `${environment.bffUrl}/api/orders`;

  async getOrders(page: number = 0, size: number = 20): Promise<Page<Order>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('sort', 'creationTime,desc');

    return firstValueFrom(this.http.get<Page<Order>>(this.apiUrl, { params }));
  }

  async createOrder(): Promise<Order> {
    // Generate a random order number for demo purposes
    const orderNumber = `ORD-${Math.floor(Math.random() * 10000)}`;
    return firstValueFrom(this.http.post<Order>(this.apiUrl, { orderNumber }));
  }
}
