package com.example.orderservice.service;

import com.example.orderservice.dto.OrderRequest;
import com.example.orderservice.dto.OrderResponse;
import com.example.orderservice.model.OrderEntity;
import com.example.orderservice.model.OrderStatus;
import com.example.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse createOrder(OrderRequest orderRequest) {
        // createdBy/updatedBy/creationTime/updateTime are populated automatically by Spring
        // Data JPA auditing (see JpaAuditingConfig) from the authenticated JWT's subject.
        OrderEntity order = OrderEntity.builder()
                .orderNumber(orderRequest.orderNumber())
                .status(OrderStatus.CREATED)
                .build();
        OrderEntity saved = orderRepository.save(order);
        return OrderResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByUserId(String userId, Pageable pageable) {
        return orderRepository.findByCreatedBy(userId, pageable)
                .map(OrderResponse::fromEntity);
    }
}
