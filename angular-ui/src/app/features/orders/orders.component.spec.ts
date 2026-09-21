import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { OrdersComponent } from './orders.component';
import { Page } from './orders.service';
import { Order } from './order.model';
import { environment } from '../../../environments/environment';

const EMPTY_PAGE: Page<Order> = { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };

const ONE_ORDER_PAGE: Page<Order> = {
  content: [
    { id: 1, orderNumber: 'ORD-1', status: 'CREATED', createdBy: 'user-1', creationTime: '2026-01-01T00:00:00Z' }
  ],
  totalElements: 1,
  totalPages: 1,
  size: 20,
  number: 0
};

describe('OrdersComponent', () => {
  let fixture: ComponentFixture<OrdersComponent>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [OrdersComponent],
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

  it('shows the empty state when there are no orders', async () => {
    fixture = TestBed.createComponent(OrdersComponent);
    fixture.detectChanges();

    httpMock.expectOne(req => req.url === `${environment.bffUrl}/api/orders`).flush(EMPTY_PAGE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No orders found');
  });

  it('lists orders returned by the backend', async () => {
    fixture = TestBed.createComponent(OrdersComponent);
    fixture.detectChanges();

    httpMock.expectOne(req => req.url === `${environment.bffUrl}/api/orders`).flush(ONE_ORDER_PAGE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();

    expect(fixture.componentInstance.orders()).toEqual(ONE_ORDER_PAGE.content);
    expect(fixture.nativeElement.textContent).toContain('ORD-1');
  });

  it('creates an order and reloads the first page', async () => {
    fixture = TestBed.createComponent(OrdersComponent);
    fixture.detectChanges();
    httpMock.expectOne(req => req.url === `${environment.bffUrl}/api/orders`).flush(EMPTY_PAGE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));

    const createPromise = fixture.componentInstance.createOrder();

    const postReq = httpMock.expectOne(
      req => req.url === `${environment.bffUrl}/api/orders` && req.method === 'POST'
    );
    postReq.flush(ONE_ORDER_PAGE.content[0]);

    const reloadReq = await vi.waitFor(() =>
      httpMock.expectOne(req => req.url === `${environment.bffUrl}/api/orders` && req.method === 'GET')
    );
    reloadReq.flush(ONE_ORDER_PAGE);

    await createPromise;
    fixture.detectChanges();

    expect(fixture.componentInstance.creating()).toBe(false);
    expect(fixture.componentInstance.orders().length).toBe(1);
  });

  it('reloads with the new page settings when the paginator changes', async () => {
    fixture = TestBed.createComponent(OrdersComponent);
    fixture.detectChanges();
    httpMock.expectOne(req => req.url === `${environment.bffUrl}/api/orders`).flush(EMPTY_PAGE);
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));

    const changePromise = fixture.componentInstance.onPageChange({
      pageIndex: 2,
      pageSize: 10,
      length: 0
    });

    const req = await vi.waitFor(() =>
      httpMock.expectOne(
        req => req.url === `${environment.bffUrl}/api/orders` && req.params.get('page') === '2' && req.params.get('size') === '10'
      )
    );
    req.flush(EMPTY_PAGE);
    await changePromise;

    expect(fixture.componentInstance.pageIndex()).toBe(2);
    expect(fixture.componentInstance.pageSize()).toBe(10);
  });
});
