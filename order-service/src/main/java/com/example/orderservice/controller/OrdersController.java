package com.example.orderservice.controller;

import com.example.orderservice.dto.OrderRequest;
import com.example.orderservice.dto.OrderResponse;
import com.example.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for order operations.
 * <p>
 * External path: /orders/ (via Gateway)
 * Internal path: /api/ (after Gateway transforms /orders/ -> /api/)
 * <p>
 * Trailing slash normalization is handled by {@code TrailingSlashFilter} in common-web.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrdersController {

    private final OrderService orderService;

    @PostMapping
    public OrderResponse createOrder(@Valid @RequestBody OrderRequest orderRequest) {
        return orderService.createOrder(orderRequest);
    }

    // Returned as PagedModel (rather than a bare Page) so the response shape -
    // { "content": [...], "page": { "size", "number", "totalElements", "totalPages" } } - is
    // explicit and doesn't depend on spring.data.web.pageable.serialization-mode.
    @GetMapping
    public PagedModel<OrderResponse> getOrders(@AuthenticationPrincipal Jwt jwt, @PageableDefault(size = 20) Pageable pageable) {
        return new PagedModel<>(orderService.getOrdersByUserId(jwt.getSubject(), pageable));
    }
}
