package com.treepeople.leapmindtts.service;

import com.treepeople.leapmindtts.config.ContextCompressProperties;
import com.treepeople.leapmindtts.config.PythonApiProperties;
import com.treepeople.leapmindtts.pojo.dto.CompressRequest;
import com.treepeople.leapmindtts.pojo.dto.CompressResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompressService {
    private final WebClient webClient;
    private final PythonApiProperties properties;
    private final ContextCompressProperties compressProperties;
    private final MeterRegistry meterRegistry;

    @CircuitBreaker(name = "contextCompress", fallbackMethod = "compressContextFallback")
    public Mono<String> compressContext(String contextText) {
        // Simple token estimation, can be replaced with a more accurate library if needed
        int estimatedTokens = (int) (contextText.length() * 0.5);
        if (estimatedTokens < compressProperties.getTokenThreshold()) {
            log.debug("Context length is below threshold, skipping compression.");
            return Mono.just(contextText);
        }

        log.debug("Context length is above threshold, attempting compression.");
        CompressRequest request = new CompressRequest(contextText, compressProperties.getMaxCompressedTokens());
        Timer.Sample sample = Timer.start(meterRegistry);

        return webClient.post()
                .uri(properties.getCompressContextUri())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(CompressResponse.class)
                .map(CompressResponse::getCompressedText)
                .doOnSuccess(r -> sample.stop(meterRegistry.timer("external.api.duration", "api", "compress", "status", "success")))
                .doOnError(e -> sample.stop(meterRegistry.timer("external.api.duration", "api", "compress", "status", "error")))
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Fallback for context compression. Returns the original text if the service fails.
     */
    public Mono<String> compressContextFallback(String contextText, Throwable t) {
        log.warn("Context compression fallback triggered: using original context. Reason: {}", t.getMessage());
        meterRegistry.counter("compress.fallback.count").increment();
        return Mono.just(contextText);
    }
}
