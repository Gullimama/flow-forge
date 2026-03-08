package com.example.payment;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

@Singleton
public class PaymentService {

    @Inject PaymentRepository paymentRepository;

    public Mono<Payment> findById(String id) {
        return paymentRepository.findById(id)
                .flatMap(this::enrich)
                .switchIfEmpty(Mono.defer(() -> lookupExternal(id)))
                .zipWith(getConfig())
                .map(t -> t.getT1().withConfig(t.getT2()));
    }

    private Mono<Payment> enrich(Payment p) { return Mono.just(p); }
    private Mono<Payment> lookupExternal(String id) { return Mono.empty(); }
    private Mono<Config> getConfig() { return Mono.just(new Config()); }
}
