export interface Order {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  createdBy: string;
  // ISO-8601 instant (e.g. "2026-01-01T00:00:00.123456Z") - the backend serializes this as a
  // java.time.Instant, always UTC with a trailing "Z", instead of the previous zone-less
  // LocalDateTime. Angular's DatePipe parses both forms identically.
  creationTime: string;
}

export type OrderStatus = 'CREATED' | 'PENDING' | 'CONFIRMED' | 'SHIPPED' | 'DELIVERED' | 'CANCELED';

export interface CreateOrderRequest {
  // The backend auto-generates orderNumber and sets createdBy from JWT
}
