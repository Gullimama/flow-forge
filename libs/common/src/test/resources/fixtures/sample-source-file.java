package com.example.service;

import java.util.List;

public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public List<Order> findActiveOrders() {
        return repository.findByStatus(OrderStatus.ACTIVE);
    }
}
