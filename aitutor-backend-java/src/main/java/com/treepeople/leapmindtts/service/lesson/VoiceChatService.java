package com.treepeople.leapmindtts.service.lesson;

import com.treepeople.leapmindtts.service.common.ContextCompressService;
import com.treepeople.leapmindtts.service.common.RedisCacheService;
import com.treepeople.leapmindtts.service.common.RequestMergeService;
import com.treepeople.leapmindtts.util.CacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * @ Package：com.treepeople.leapmindtts.service
 * @ Project：leapmind-tts - 语音对话
 * @ Description: 语音对话服务
 * @ Date：2025/8/8
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceChatService {

    private final AIModelService aiModelService;
    private final TextToSpeechService ttsService;
    private final RequestMergeService requestMergeService;
    private final RedisCacheService redisCacheService;
    private final ContextCompressService contextCompressService;

    private static final String CACHE_KEY_PREFIX = "voice_chat:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final String NULL_PLACEHOLDER = "__NULL__";

    /**
     * 处理语音对话
     * 包含请求合并和Redis缓存逻辑
     */
    public Mono<String> processVoiceChat(String userId, String question) {
        log.info("语音对话服务：处理对话请求，用户ID: {}, 问题: {}", userId, question);

        String cacheKey = CacheKeyBuilder.QUESTION_CACHE_PREFIX + CacheKeyBuilder.questionHash(question);

        // 1. 检查Redis缓存
        String cachedAnswer = redisCacheService.get(cacheKey);
        if (cachedAnswer != null) {
            if (NULL_PLACEHOLDER.equals(cachedAnswer)) {
                log.info("Redis缓存命中（空值），Key: {}", cacheKey);
                return Mono.empty(); // 或者返回一个代表“无答案”的特定响应
            }
            log.info("Redis缓存命中，Key: {}", cacheKey);
            return Mono.just(cachedAnswer);
        }

        // 2. 如果缓存未命中，则执行请求合并逻辑
        String dedupKey = CacheKeyBuilder.dedupKey(userId, question);
        RequestMergeService.RequestMergeResult mergeResult = requestMergeService.tryMerge(dedupKey);

        if (mergeResult.isFirst()) {
            // 这是第一个请求，执行实际的AI调用
            log.info("请求合并：首个请求，Key: {}", dedupKey);
            return contextCompressService.compressContext(question)
                    .flatMap(compressedQuestion -> 
                        aiModelService.getAIResponse(compressedQuestion)
                            .doOnSuccess(answer -> {
                                if (StringUtils.hasText(answer)) {
                                    log.info("AI回答: {}", answer);
                                    redisCacheService.set(cacheKey, answer, CACHE_TTL);
                                    requestMergeService.completeRequest(dedupKey, answer);
                                } else {
                                    log.warn("AI未返回有效回答，设置空值占位符，Key: {}", cacheKey);
                                    redisCacheService.setNullPlaceholder(cacheKey);
                                    requestMergeService.completeRequest(dedupKey, ""); // 用空字符串完成
                                }
                            })
                    )
                    .doOnError(error -> {
                        log.error("处理语音对话失败，用户ID: {}, 问题: {}", userId, question, error);
                        requestMergeService.failRequest(dedupKey, error);
                    });
        } else {
            // 这是合并的请求，等待第一个请求的结果
            log.info("请求合并：命中，Key: {}", dedupKey);
            return Mono.fromFuture(mergeResult.getFuture());
        }
    }

    // 测试方法，不带提示词
    public Mono<String> processVoiceChatNoPrompt(String courseId, String question) {
        log.info("语音对话服务：处理对话请求，会话ID: {}, 问题: {}", courseId, question);

        return aiModelService.getAIResponseNoPrompt(question)
                .doOnNext(answer -> log.info("AI回答: {}", answer))
                .doOnError(error -> log.error("处理语音对话失败，会话ID: {}, 问题: {}", courseId, question, error));
    }

    /**
     * 合成语音音频
     * 将文本转换为语音
     */
    public Mono<byte[]> synthesizeVoiceAudio(String text) {
        log.info("语音对话服务：合成语音音频，文本长度: {} 字符", text.length());

        return ttsService.synthesizeSpeech(text)
                .doOnNext(audioData -> log.info("语音合成完成，音频大小: {} bytes", audioData.length))
                .doOnError(error -> log.error("语音合成失败，文本: {}", text, error));
    }
}
