package com.treepeople.leapmindtts.controller.lesson;

import com.treepeople.leapmindtts.exception.BizErrorCode;
import com.treepeople.leapmindtts.pojo.ApiResponse;
import com.treepeople.leapmindtts.pojo.dto.VoiceChatRequest;
import com.treepeople.leapmindtts.pojo.dto.VoiceSynthesisRequest;
import com.treepeople.leapmindtts.service.common.MetricsService;
import com.treepeople.leapmindtts.service.lesson.VoiceChatService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @ Package：com.treepeople.leapmindtts.controller
 * @ Project：leapmind-tts - 语音对话
 * @ Description: 语音对话控制器
 * @ Date：2025/8/8
 */

@Slf4j
@RestController
@RequestMapping("/api/voice-chat")
@RequiredArgsConstructor
public class VoiceChatController {

    private final VoiceChatService voiceChatService;
    private final MetricsService metricsService;

    //use:语音问答接口 111

    /**
     * 处理语音对话请求
     * 接收用户问题，调用AI服务获取回答
     * 注意：从响应式改为同步调用，解决Spring MVC与WebFlux混用导致的403问题
     */
    @PostMapping("/ask")
    @RateLimiter(name = "userQuestionLimiter", fallbackMethod = "rateLimitFallback", keyResolver = "userKeyResolver")
    public ApiResponse<String> handleVoiceChat(
            @RequestBody @Valid VoiceChatRequest request, HttpServletRequest httpRequest) {
        metricsService.incrementQuestionProcessed("voice_chat", "received");
        log.info("接收到语音对话请求，会话ID: {}, 问题: {}",
                request.getCourseId(), request.getQuestion());

        try {
            // 从安全上下文中获取用户ID，如果不存在，则传递一个默认值或标识
            String userId = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                                    .map(Authentication::getName)
                                    .orElse(httpRequest.getRemoteAddr());

            // 使用.block()将响应式调用转换为同步调用
            String answer = voiceChatService.processVoiceChat(userId, request.getQuestion())
                    .block();

            log.info("语音对话处理完成，会话ID: {}, AI回答: {}",
                    request.getCourseId(), answer);
            metricsService.incrementQuestionProcessed("voice_chat", "success");
            return ApiResponse.success(answer);

        } catch (Exception e) {
            log.error("处理语音对话失败", e);
            metricsService.incrementQuestionProcessed("voice_chat", "error");
            return ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "处理问题时出现错误：" + e.getMessage());
        }
    }

    /**
     * 限流降级方法
     * 当用户请求过于频繁时，返回此响应
     */
    public ApiResponse<String> rateLimitFallback(VoiceChatRequest request, HttpServletRequest httpRequest, Throwable t) {
        log.warn("触发 Resilience4j 流量限制，请求拒绝。用户标识: {}, 异常信息: {}",
                 httpRequest.getRemoteAddr(), t.getMessage());
        metricsService.incrementQuestionProcessed("voice_chat", "rate_limited");
        return ApiResponse.error(BizErrorCode.RATE_LIMITED.getCode(), BizErrorCode.RATE_LIMITED.getMessage());
    }


    //use:语音合成接口

    /**
     * 合成语音
     * 将文本转换为语音音频
     * 注意：从响应式改为同步调用，解决Spring MVC与WebFlux混用导致的403问题
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesizeVoice(
            @RequestBody @Valid VoiceSynthesisRequest request) {
        log.info("接收到语音合成请求，会话ID: {}, 文本长度: {}",
                request.getCourseId(), request.getText().length());

        try {
            // 使用.block()将响应式调用转换为同步调用
            byte[] audioData = voiceChatService.synthesizeVoiceAudio(request.getText())
                    .block();

            log.info("语音合成完成，会话ID: {}, 音频大小: {} bytes",
                    request.getCourseId(), audioData.length);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .body(audioData);

        } catch (Exception error) {
            log.error("语音合成失败，会话ID: {}", request.getCourseId(), error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new byte[0]);
        }
    }
}
