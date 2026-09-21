import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface RegisterRequest {
  email: string;
  firstName: string;
  lastName: string;
  mobileNumber?: string;
  gender: 'MALE' | 'FEMALE';
  age: number;
}

export interface RegisterResponse {
  message: string;
  email: string;
}

export interface ConfirmResponse {
  message: string;
  email: string;
}

@Injectable({
  providedIn: 'root'
})
export class RegisterService {
  private http = inject(HttpClient);
  // Public endpoints use pattern: /bff/public/{service}/{path}
  // This maps to gateway: /{service}/public/{path}
  private readonly apiUrl = `${environment.bffUrl}/public/profile`;

  async register(request: RegisterRequest): Promise<RegisterResponse> {
    return firstValueFrom(
      this.http.post<RegisterResponse>(`${this.apiUrl}/register`, request)
    );
  }

  async confirm(token: string): Promise<ConfirmResponse> {
    return firstValueFrom(
      this.http.get<ConfirmResponse>(`${this.apiUrl}/confirm`, {
        params: { token }
      })
    );
  }
}
