package com.treepeople.leapmindtts.aspect;

import com.treepeople.leapmindtts.service.MetricsService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CircuitBreakerEventListener {

    private final CircuitBreakerRegistry registry;
    private final MetricsService metrics;

    @PostConstruct
    public void registerEventListener() {
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
