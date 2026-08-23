package com.treepeople.leapmindtts.service.lesson;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @ Package：com.treepeople.leapmindtts.service.Impl
 * @ Project：leapmind-tts
 * @ Description:
 * @ Date：2025/7/14  22:33
 */
@Service
@Slf4j
public class TextToSpeechService {
    // 阿里云实时语音合成API - 使用正确的端点
    private static final String TTS_API_URL = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts";

    @Value("${tts.app.key:}")
    private String appKey;

    @Value("${tts.voice:xiaoyun}")
    private String voiceName;

    private final WebClient webClient;
    private final AliyunTokenService tokenService;
    private static final Map<String, String> VOICE_ALIASES = Map.of(
            "young-female-warm", "zhixiaoxia",
            "young-female-clear", "zhixiaobai",
            "young-female-natural", "zhixiaoxia"
    );

    public TextToSpeechService(WebClient.Builder webClientBuilder, AliyunTokenService tokenService) {
        this.webClient = webClientBuilder.build();
        this.tokenService = tokenService;
    }

    public Mono<byte[]> synthesizeSpeech(String text) {
        return synthesizeSpeech(text, voiceName, 1.0);
    }

    public Mono<byte[]> synthesizeSpeech(String text, String requestedVoice, double speed) {
        // 检查输入文本是否为空
        if (text == null || text.trim().isEmpty()) {
            log.warn("文本为空，跳过语音合成");
            return Mono.just(new byte[0]); // 返回空音频数据
        }

        // 阿里云TTS短文本合成有300字符的限制，长文本需要分段合成后合并
        final String originalText = text;
        final String selectedVoice = requestedVoice == null
                || requestedVoice.isBlank()
                || "default".equalsIgnoreCase(requestedVoice)
                ? voiceName
                : requestedVoice;
        final String voice = VOICE_ALIASES.getOrDefault(selectedVoice, selectedVoice);
        final double normalizedSpeed = Math.max(0.5, Math.min(2.0, speed));

        // 如果文本长度在限制内，直接合成
        if (originalText.length() <= 290) {
            return synthesizeSingleSegment(originalText, voice, normalizedSpeed);
        }

        // 长文本：分段合成后合并音频
        log.info("文本过长（{} > 290），启用分段合成", originalText.length());
        List<String> segments = splitLongText(originalText, 290);

        if (segments.size() == 1) {
            return synthesizeSingleSegment(segments.get(0), voice, normalizedSpeed);
        }

        // 依次合成每段，合并结果
        return synthesizeAndMergeSegments(segments, voice, normalizedSpeed);
    }

    /**
     * 合成单个文本段落
     *
     * @param text 要合成的文本（长度应在限制内）
     * @return 音频数据
     */
    private Mono<byte[]> synthesizeSingleSegment(String text, String voice, double speed) {
        log.debug("发送TTS请求: text长度={}, voice={}, speed={}, format={}", text.length(), voice, speed, "wav");

        // 动态获取Token并发送请求
        return tokenService.getToken()
                .flatMap(token -> {
                    // 根据阿里云TTS API官方文档构建请求格式
                    // 使用最简单的JSON格式，确保兼容性
                    Map<String, Object> request = new LinkedHashMap<>();

                    // 必需字段
                    request.put("text", text);
                    request.put("voice", voice);
                    request.put("format", "wav");

                    // 添加一些可选参数来提高兼容性
                    request.put("sample_rate", 16000);
                    request.put("volume", 50);
                    request.put("speech_rate", (int) Math.round((speed - 1.0) * 500));

                    if (appKey != null && !appKey.trim().isEmpty()) {
                        request.put("appkey", appKey);
                        log.debug("使用appkey: {}", appKey.substring(0, Math.min(4, appKey.length())) + "***");
                    }

                    log.debug("最终TTS请求JSON: {}", request);

                    return webClient.post()
                            .uri(TTS_API_URL)
                            .header("X-NLS-Token", token)
                            .header("Content-Type", "application/json")
                            .header("Accept", "audio/wav")
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(byte[].class)
                            // 增加重试机制，应对网络不稳定和临时服务问题
                            .retry(3) // 最多重试3次
                            .timeout(java.time.Duration.ofSeconds(120)) // 单次请求最大等待120秒
                            .doOnSuccess(audioData -> {
                                log.info("TTS语音合成成功，音频数据大小: {} bytes", audioData.length);
                            })
                            .onErrorMap(error -> {
                                log.error("TTS请求失败", error);

                                // 处理不同类型的错误，提供更详细的错误信息
                                if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                                    var webError = (org.springframework.web.reactive.function.client.WebClientResponseException) error;
                                    String errorBody = webError.getResponseBodyAsString();
                                    log.error("TTS API错误响应 [{}]: {}", webError.getStatusCode(), errorBody);

                                    // 根据不同的HTTP状态码提供更具体的错误信息
                                    if (webError.getStatusCode().value() == 429) {
                                        return new RuntimeException("TTS服务请求过于频繁，请稍后重试");
                                    } else if (webError.getStatusCode().value() == 401) {
                                        return new RuntimeException("TTS服务认证失败，请检查Token配置");
                                    } else if (webError.getStatusCode().value() >= 500) {
                                        return new RuntimeException("TTS服务暂时不可用，请稍后重试");
                                    } else {
                                        return new RuntimeException("TTS合成失败: " + webError.getStatusCode() + " - " + errorBody);
                                    }
                                } else if (error instanceof java.util.concurrent.TimeoutException) {
                                    log.error("TTS请求超时: {}", error.getMessage());
                                    return new RuntimeException("TTS服务连接超时，可能是网络问题，请检查网络连接后重试");
                                } else {
                                    String errorMsg = error.getMessage();
                                    if (errorMsg != null) {
                                        if (errorMsg.contains("timeout") || errorMsg.contains("handshake timed out")) {
                                            log.error("TTS请求超时: {}", errorMsg);
                                            return new RuntimeException("TTS服务连接超时，可能是网络问题，请检查网络连接后重试");
                                        } else if (errorMsg.contains("Connection refused") || errorMsg.contains("UnknownHostException")) {
                                            log.error("TTS服务连接失败: {}", errorMsg);
                                            return new RuntimeException("无法连接到TTS服务，请检查网络连接");
                                        }
                                    }
                                    return new RuntimeException("TTS合成失败: " + (errorMsg != null ? errorMsg : "未知错误"));
                                }
                            });
                });
    }


    /**
     * 将长文本分割为不超过 maxLength 的段，尽量在句子结束符处切分
     *
     * @param text 原始文本
     * @param maxLength 每段最大长度
     * @return 分段列表
     */
    private List<String> splitLongText(String text, int maxLength) {
        List<String> segments = new ArrayList<>();
        if (text == null || text.length() <= maxLength) {
            if (text != null) segments.add(text);
            return segments;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());

            if (end < text.length()) {
                // 在 [start, end] 范围内查找最后一个句子结束符
                String chunk = text.substring(start, end);
                int[] endPositions = {
                    chunk.lastIndexOf('。'),
                    chunk.lastIndexOf('！'),
                    chunk.lastIndexOf('？'),
                    chunk.lastIndexOf(';'),
                    chunk.lastIndexOf('；'),
                    chunk.lastIndexOf(','),
                    chunk.lastIndexOf('，')
                };

                int bestEnd = -1;
                for (int pos : endPositions) {
                    if (pos > bestEnd) bestEnd = pos;
                }

                // 找到句子结束符且位置不太靠前（至少保留一半长度）
                if (bestEnd > maxLength / 2) {
                    end = start + bestEnd + 1;
                } else {
                    // 尝试在空格处截断
                    int lastSpace = chunk.lastIndexOf(' ');
                    if (lastSpace > maxLength * 2 / 3) {
                        end = start + lastSpace;
                    }
                    // 否则直接在 maxLength 处截断
                }
            }

            String segment = text.substring(start, end).trim();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
            start = end;
        }

        log.info("长文本分段完成，原文{}字，分为{}段", text.length(), segments.size());
        return segments;
    }

    /**
     * 依次合成多段文本并合并音频
     */
    private Mono<byte[]> synthesizeAndMergeSegments(List<String> segments, String voice, double speed) {
        Mono<byte[]> result = synthesizeSingleSegment(segments.get(0), voice, speed);

        for (int i = 1; i < segments.size(); i++) {
            final int index = i;
            result = result.flatMap(prevAudio ->
                    synthesizeSingleSegment(segments.get(index), voice, speed)
                            .map(nextAudio -> {
                                // 合并前一段和当前段的音频
                                List<byte[]> toMerge = new ArrayList<>();
                                toMerge.add(prevAudio);
                                toMerge.add(nextAudio);
                                byte[] merged = com.treepeople.leapmindtts.util.WavMergeUtil.mergeWavSegments(toMerge);
                                log.debug("音频合并完成，段 {}/{}", index + 1, segments.size());
                                return merged;
                            })
            );
        }

        return result.doOnSuccess(audioData ->
                log.info("分段合成并合并完成，共{}段，总音频大小: {} bytes", segments.size(), audioData.length));
    }


    /**
     * 智能截断文本（已废弃，保留用于向后兼容）
     * 尽量在句子结束符处截断，保持文本的完整性和可读性
     *
     * @param text 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    @SuppressWarnings("unused")
    private String smartTruncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }

        // 在最大长度范围内查找最后一个句子结束符
        String truncated = text.substring(0, maxLength);

        // 按优先级查找句子结束符：句号 > 感叹号 > 问号 > 分号 > 逗号
        int[] endPositions = {
            truncated.lastIndexOf('。'),
            truncated.lastIndexOf('！'),
            truncated.lastIndexOf('？'),
            truncated.lastIndexOf(';'),
            truncated.lastIndexOf('；'),
            truncated.lastIndexOf(','),
            truncated.lastIndexOf('，')
        };

        // 找到最靠后的句子结束符
        int bestEndPosition = -1;
        for (int pos : endPositions) {
            if (pos > bestEndPosition) {
                bestEndPosition = pos;
            }
        }

        // 如果找到句子结束符，并且位置不太靠前（至少保留一半长度）
        if (bestEndPosition > maxLength / 2) {
            return text.substring(0, bestEndPosition + 1);
        }

        // 如果没有找到合适的句子结束符，尝试在空格处截断
        int lastSpacePosition = truncated.lastIndexOf(' ');
        if (lastSpacePosition > maxLength * 2 / 3) {
            return text.substring(0, lastSpacePosition);
        }

        // 最后的选择：直接截断并添加省略号
        return truncated.trim() + "...";
    }

}
