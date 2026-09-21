package com.example.orderservice.dto;

import com.example.orderservice.model.OrderEntity;
import com.example.orderservice.model.OrderStatus;

import java.time.Instant;

public record OrderResponse(
        String orderNumber,
        OrderStatus status,
        String createdBy,
        Instant creationTime
) {

    public static OrderResponse fromEntity(OrderEntity entity) {
        return new OrderResponse(
                entity.getOrderNumber(),
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getCreationTime()
        );
    }
}
