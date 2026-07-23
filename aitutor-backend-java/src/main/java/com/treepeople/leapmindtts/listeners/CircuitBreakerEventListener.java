package com.treepeople.leapmindtts.listeners;

import com.treepeople.leapmindtts.service.common.MetricsService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class CircuitBreakerEventListener {

    private final CircuitBreakerRegistry registry;
    private final MetricsService metrics;

    public CircuitBreakerEventListener(CircuitBreakerRegistry registry, MetricsService metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @PostConstruct
    public void register() {
        registry.getAllCircuitBreakers().forEach(cb -> {
            cb.getEventPublisher().onStateTransition(event -> {
                metrics.recordCircuitBreakerState(
                    event.getCircuitBreakerName(),
                    event.getStateTransition().getFromState().name(),
                    event.getStateTransition().getToState().name()
                );
            });
        });
    }
}
