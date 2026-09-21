package com.example.orderservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderRequest(

        @NotBlank(message = "Order number is required")
        @Size(max = 50, message = "Order number must not exceed 50 characters")
        String orderNumber
) {
}
