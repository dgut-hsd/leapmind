
package com.treepeople.leapmindtts;

import com.treepeople.leapmindtts.common.util.CacheKeyBuilder;
import com.treepeople.leapmindtts.service.ContextCompressService;
import com.treepeople.leapmindtts.service.RedisCacheService;
import com.treepeople.leapmindtts.service.RequestMergeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        // For faster tests
        "resilience4j.circuitbreaker.instances.contextCompress.slidingWindowSize=4",
        "resilience4j.circuitbreaker.instances.contextCompress.minimumNumberOfCalls=2",
        "resilience4j.circuitbreaker.instances.contextCompress.waitDurationInOpenState=2s"
})
public class ChatPipelineIntegrationTest {

    // region Test Infrastructure Setup
    private static MockWebServer mockPythonApi;

    @Autowired private TestChatPipeline chatPipeline;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private CacheKeyBuilder cacheKeyBuilder;

    private final String TEST_QUESTION = "What is Spring Boot?";
    private final String TEST_ANSWER = "This is a mock answer.";

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("python.api.base-url", () -> mockPythonApi.url("/").toString());
    }

    @BeforeAll
    static void setUp() throws IOException {
        mockPythonApi = new MockWebServer();
        mockPythonApi.start();
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockPythonApi.shutdown();
    }

    @BeforeEach
    void resetState() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        while (mockPythonApi.getRequestCount() > 0) {
            try { mockPythonApi.takeRequest(); } catch (InterruptedException e) { /* ignore */ }
        }
    }
    // endregion

    // region Test Cases

    @Test
    @DisplayName("场景1: 常规请求应成功并缓存结果")
    void test_1_normalRequestFlow() throws Exception {
        mockPythonApi.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(new LLMResponse(TEST_ANSWER)))
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(chatPipeline.ask(TEST_QUESTION))
                .expectNext(TEST_ANSWER)
                .verifyComplete();

        String cacheKey = cacheKeyBuilder.buildForQuestion(TEST_QUESTION);
        String cachedValue = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cachedValue).isEqualTo(TEST_ANSWER);
        assertThat(mockPythonApi.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景2: 并发请求应合并为一次调用")
    void test_2_requestMerging() {
        mockPythonApi.enqueue(new MockResponse()
                .setBodyDelay(200, TimeUnit.MILLISECONDS)
                .setBody("{ \"answer\": \"" + TEST_ANSWER + "\" }")
                .addHeader("Content-Type", "application/json"));

        Mono<String> mono1 = chatPipeline.ask(TEST_QUESTION);
        Mono<String> mono2 = chatPipeline.ask(TEST_QUESTION);

        StepVerifier.create(Mono.zip(mono1, mono2))
                .assertNext(tuple -> {
                    assertThat(tuple.getT1()).isEqualTo(TEST_ANSWER);
                    assertThat(tuple.getT2()).isEqualTo(TEST_ANSWER);
                })
                .verifyComplete();

        assertThat(mockPythonApi.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景4: 失败请求应触发穿透保护")
    void test_4_cachePenetrationProtection() {
        mockPythonApi.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(chatPipeline.ask(TEST_QUESTION))
                .expectError()
                .verify();

        String cacheKey = cacheKeyBuilder.buildForQuestion(TEST_QUESTION);
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isEqualTo("__NULL__");

        StepVerifier.create(chatPipeline.ask(TEST_QUESTION))
                .verifyComplete();

        assertThat(mockPythonApi.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景5: 压缩服务失败应降级")
    void test_5_compressionServiceFallback() throws Exception {
        mockPythonApi.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        mockPythonApi.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(new LLMResponse(TEST_ANSWER)))
                .addHeader("Content-Type", "application/json"));

        String longQuestion = "This is a very long question...".repeat(20);

        StepVerifier.create(chatPipeline.ask(longQuestion))
                .expectNext(TEST_ANSWER)
                .verifyComplete();

        assertThat(mockPythonApi.getRequestCount()).isEqualTo(2);
        assertThat(mockPythonApi.takeRequest().getPath()).isEqualTo("/api/internal/ai/compress-context");
        assertThat(mockPythonApi.takeRequest().getPath()).isEqualTo("/ask-llm");
    }

    @Test
    @DisplayName("场景6: 熔断器应能打开并自愈")
    void test_6_circuitBreakerLifecycle() throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("contextCompress");

        mockPythonApi.enqueue(new MockResponse().setResponseCode(500));
        mockPythonApi.enqueue(new MockResponse().setResponseCode(500));

        for (int i = 0; i < 2; i++) {
            StepVerifier.create(chatPipeline.ask(TEST_QUESTION)).expectError().verify();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS)
                .until(() -> circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN);

        mockPythonApi.enqueue(new MockResponse().setBody(objectMapper.writeValueAsString(new CompressResponse("ok"))));
        mockPythonApi.enqueue(new MockResponse().setBody(objectMapper.writeValueAsString(new LLMResponse(TEST_ANSWER))));

        StepVerifier.create(chatPipeline.ask(TEST_QUESTION))
                .expectNext(TEST_ANSWER)
                .verifyComplete();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // endregion

    // region Test-Specific DTOs and Pipeline

    @Data @NoArgsConstructor @AllArgsConstructor static class QuestionRequest { private String question; private String userId; }
    @Data @NoArgsConstructor @AllArgsConstructor static class LLMResponse { private String answer; }
    @Data @NoArgsConstructor @AllArgsConstructor static class CompressResponse { private String compressedText; }

    @TestConfiguration
    static class TestPipelineConfiguration {
        @Bean
        public TestChatPipeline testChatPipeline(RequestMergeService mergeService, RedisCacheService cacheService, ContextCompressService compressService, WebClient.Builder webClientBuilder, CacheKeyBuilder keyBuilder) {
            String baseUrl = mockPythonApi.url("/").toString();
            WebClient webClient = webClientBuilder.baseUrl(baseUrl).build();
            return new TestChatPipeline(mergeService, cacheService, compressService, webClient, keyBuilder);
        }
    }

    @Service @RequiredArgsConstructor @Slf4j
    public static class TestChatPipeline {
        private final RequestMergeService mergeService;
        private final RedisCacheService cacheService;
        private final ContextCompressService compressService;
        private final WebClient webClient;
        private final CacheKeyBuilder cacheKeyBuilder;

        public Mono<String> ask(String question) {
            String dedupKey = cacheKeyBuilder.buildForDeduplication(question);
            RequestMergeService.RequestMergeResult mergeResult = mergeService.tryMerge(dedupKey);

            if (!mergeResult.isFirst()) {
                return Mono.fromFuture(mergeResult.getFuture());
            }

            return Mono.defer(() -> {
                        String cacheKey = cacheKeyBuilder.buildForQuestion(question);
                        
                        if (redisTemplate.hasKey(cacheKey)) {
                            String cachedValue = cacheService.get(cacheKey);
                            return "__NULL__".equals(cachedValue) ? Mono.empty() : Mono.just(cachedValue);
                        }

                        return compressService.compressContext(question)
                                .flatMap(this::callAIModel)
                                .doOnSuccess(answer -> {
                                    cacheService.set(cacheKey, answer, Duration.ofHours(1));
                                    mergeService.completeRequest(dedupKey, answer);
                                })
                                .doOnError(e -> {
                                    cacheService.setNullPlaceholder(cacheKey);
                                    mergeService.failRequest(dedupKey, e);
                                });
                    });
        }

        private Mono<String> callAIModel(String content) {
            return webClient.post().uri("/ask-llm")
                    .bodyValue(new QuestionRequest(content, "test-user"))
                    .retrieve()
                    .bodyToMono(LLMResponse.class)
                    .map(LLMResponse::getAnswer);
        }
    }
    // endregion
}
