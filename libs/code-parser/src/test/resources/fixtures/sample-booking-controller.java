package com.example.booking;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

@Controller("/bookings")
public class BookingController {

    @Inject BookingRepository repository;
    @Inject PaymentClient paymentClient;

    @Get("/{id}")
    public Mono<Booking> getBooking(String id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Booking not found")));
    }

    @Post
    public Mono<HttpResponse<Booking>> createBooking(@Body BookingRequest req) {
        return repository.save(req.toEntity())
                .map(Booking::fromEntity)
                .map(HttpResponse::created);
    }
}
