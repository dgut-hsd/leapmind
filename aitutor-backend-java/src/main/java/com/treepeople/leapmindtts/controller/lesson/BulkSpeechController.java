package com.treepeople.leapmindtts.controller.lesson;

import com.treepeople.leapmindtts.pojo.dto.*;
import com.treepeople.leapmindtts.service.admin.BulkSpeechCache;
import com.treepeople.leapmindtts.service.admin.BulkSpeechService;
import com.treepeople.leapmindtts.service.lesson.PageLevelAudioService;
import com.treepeople.leapmindtts.service.lesson.VoiceDatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量语音合成控制器
 */
@RestController
@RequestMapping("/api/speech")
@RequiredArgsConstructor
@Slf4j
@Validated
public class BulkSpeechController {

    private final BulkSpeechService bulkSpeechService;
    private final PageLevelAudioService pageLevelAudioService;
    private final VoiceDatabaseService voiceDatabaseService;
    private final BulkSpeechCache bulkSpeechCache;

    //use：批量音频合成
    /**
     * 批量语音合成接口
     */
    @PostMapping("/bulk-synthesis")
    public ResponseEntity<BulkSynthesisResponse> bulkSynthesis(@Valid @RequestBody BulkSynthesisRequest request,
                                                                HttpServletRequest httpRequest) {
        log.info("接收到批量语音合成请求，PPT标题: {}, slides数量: {}", request.getTitle(), request.getSlides().size());

        // 提取用户ID（JWT 认证后由 JwtAuthenticationFilter 设置到 request attribute）
        Long userId = extractUserId(httpRequest);

        // 限流检查
        int totalTextLength = calculateTotalTextLength(request);
        bulkSpeechCache.checkRateLimit(userId, totalTextLength);

        // 幂等检查：防止重复提交相同请求
        String requestHash = computeRequestHash(request);
        String existingCourseId = bulkSpeechCache.tryAcquireIdempotency(requestHash);
        if (existingCourseId != null && !"PROCESSING".equals(existingCourseId)) {
            log.info("幂等命中，返回已有会话: {}", existingCourseId);
            return ResponseEntity.ok(BulkSynthesisResponse.builder()
                    .courseId(existingCourseId)
                    .status("COMPLETED")
                    .message("请求已处理过，返回已有结果")
                    .startTime(LocalDateTime.now())
                    .build());
        }

        try {
            BulkSynthesisResponse response = bulkSpeechService.processBulkSynthesis(request, userId);
            log.info("批量语音合成请求处理成功，会话ID: {}", response.getCourseId());
            // 更新幂等状态
            bulkSpeechCache.updateIdempotency(requestHash, response.getCourseId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // 处理失败，释放幂等锁以允许重试
            bulkSpeechCache.releaseIdempotency(requestHash);
            log.error("批量语音合成请求处理失败", e);
            return ResponseEntity.internalServerError()
                    .body(BulkSynthesisResponse.builder()
                            .status("FAILED")
                            .message("处理失败: " + e.getMessage())
                            .build());
        }
    }

    // use:获取音频片段信息
    /**
     * 查询指定页面的音频信息和片段元数据
     * 数据库存储的是页面级音频，返回的是该页面的音频信息和片段元数据
     */
    @GetMapping("/ppt/{courseId}/page/{pageNumber}")
    public ResponseEntity<List<PPTAudioSegment>> getPageAudioSegments(
            @PathVariable @NotBlank String courseId,
            @PathVariable @NotNull Integer pageNumber,
            HttpServletRequest httpRequest) {

        log.info("查询页面音频信息，会话ID: {}, 页码: {}", courseId, pageNumber);

        // IDOR 防护：校验会话归属
        if (!checkSessionOwnership(courseId, httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            List<PPTAudioSegment> segments = bulkSpeechService.getPageAudioSegments(courseId, pageNumber);
            if (segments.isEmpty()) {
                log.warn("未找到指定页面的音频信息，会话ID: {}, 页码: {}", courseId, pageNumber);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(segments);
        } catch (Exception e) {
            log.error("查询页面音频信息失败，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return ResponseEntity.internalServerError().build();
        }
    }


    /**
     * 查询整个PPT的音频信息和统计数据
     */
    @GetMapping("/ppt/{courseId}")
    public ResponseEntity<PPTAudioInfo> getPPTAudioInfo(@PathVariable @NotBlank String courseId,
                                                         HttpServletRequest httpRequest) {

        log.info("查询PPT音频信息，会话ID: {}", courseId);

        // IDOR 防护：校验会话归属
        if (!checkSessionOwnership(courseId, httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            PPTAudioInfo audioInfo = bulkSpeechService.getPPTAudioInfo(courseId);
            if (audioInfo == null) {
                log.warn("未找到指定的PPT音频信息，会话ID: {}", courseId);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(audioInfo);
        } catch (Exception e) {
            log.error("查询PPT音频信息失败，会话ID: {}", courseId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    //use:获取页面音频,合并音频
    /**
     * 播放指定页面的完整音频文件（流式输出，避免整页音频加载到内存导致 OOM）
     * 注意：现在数据库存储的是页面级音频，返回整个页面的合并音频
     */
    @GetMapping("/ppt/{courseId}/page/{pageNumber}/audio")
    public ResponseEntity<StreamingResponseBody> playPageAudio(
            @PathVariable @NotBlank String courseId,
            @PathVariable @NotNull Integer pageNumber,
            HttpServletRequest httpRequest) {

        log.info("播放页面音频，会话ID: {}, 页码: {}", courseId, pageNumber);

        // IDOR 防护：校验会话归属
        if (!checkSessionOwnership(courseId, httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 轻量存在性检查（不加载音频数据）
        if (!pageLevelAudioService.hasPageAudio(courseId, pageNumber)) {
            log.warn("未找到指定页面的音频数据或音频数据为空，会话ID: {}, 页码: {}", courseId, pageNumber);
            return ResponseEntity.notFound().build();
        }

        // 流式输出：逐片段加载并写入响应流，内存峰值 = 单个最大片段
        StreamingResponseBody responseBody = outputStream ->
                pageLevelAudioService.streamPageAudio(courseId, pageNumber, outputStream);

        // 设置音频响应头
        return ResponseEntity.ok()
                .header("Content-Type", "audio/wav")
                .header("Cache-Control", "public, max-age=3600")
                .header("Accept-Ranges", "bytes")
                .body(responseBody);
    }

    //分离式批量语音合成接口

    /**
     * 批量文本预处理接口（不进行语音合成）
     * 处理文本润色和分句，保存到数据库等待审核
     */
    @PostMapping("/bulk-preprocessing")
    public ResponseEntity<BulkPreprocessingResponse> bulkPreprocessing(@Valid @RequestBody BulkSynthesisRequest request,
                                                                        HttpServletRequest httpRequest) {
        log.info("接收到批量文本预处理请求，PPT标题: {}, slides数量: {}", request.getTitle(), request.getSlides().size());

        Long userId = extractUserId(httpRequest);

        // 限流检查
        int totalTextLength = calculateTotalTextLength(request);
        bulkSpeechCache.checkRateLimit(userId, totalTextLength);

        // 幂等检查：防止重复提交相同请求
        String requestHash = computeRequestHash(request);
        String existingCourseId = bulkSpeechCache.tryAcquireIdempotency(requestHash);
        if (existingCourseId != null && !"PROCESSING".equals(existingCourseId)) {
            log.info("预处理幂等命中，返回已有会话: {}", existingCourseId);
            return ResponseEntity.ok(BulkPreprocessingResponse.builder()
                    .courseId(existingCourseId)
                    .status("SUCCESS")
                    .message("请求已处理过，返回已有结果")
                    .startTime(LocalDateTime.now())
                    .endTime(LocalDateTime.now())
                    .build());
        }

        try {
            BulkPreprocessingResponse response = bulkSpeechService.processBulkPreprocessing(request, userId);
            log.info("批量文本预处理请求处理完成，会话ID: {}, 状态: {}", response.getCourseId(), response.getStatus());
            bulkSpeechCache.updateIdempotency(requestHash, response.getCourseId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            bulkSpeechCache.releaseIdempotency(requestHash);
            log.error("批量文本预处理请求处理失败", e);
            return ResponseEntity.internalServerError()
                    .body(BulkPreprocessingResponse.builder()
                            .status("FAILED")
                            .message("处理失败: " + e.getMessage())
                            .startTime(LocalDateTime.now())
                            .endTime(LocalDateTime.now())
                            .build());
        }
    }

    /**
     * 执行批量语音合成接口（基于已审核通过的文本）
     */
    @PostMapping("/bulk-synthesis-execute/{courseId}")
    public ResponseEntity<BulkSynthesisResponse> executeBulkSynthesis(
            @PathVariable @NotBlank String courseId,
            HttpServletRequest httpRequest) {
        log.info("接收到批量语音合成执行请求，会话ID: {}", courseId);

        // IDOR 防护：校验会话归属
        if (!checkSessionOwnership(courseId, httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 限流检查
        Long userId = extractUserId(httpRequest);
        bulkSpeechCache.checkRateLimit(userId, 1);

        try {
            BulkSynthesisResponse response = bulkSpeechService.executeBulkSynthesis(courseId);
            log.info("批量语音合成执行完成，会话ID: {}, 状态: {}", courseId, response.getStatus());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("批量语音合成执行失败，会话ID: {}", courseId, e);
            return ResponseEntity.internalServerError()
                    .body(BulkSynthesisResponse.builder()
                            .courseId(courseId)
                            .status("FAILED")
                            .message("合成失败: " + e.getMessage())
                            .startTime(LocalDateTime.now())
                            .build());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 HTTP 请求中提取用户 ID（由 JwtAuthenticationFilter 设置）
     */
    private Long extractUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId instanceof Long value) {
            return value;
        }
        // 未认证用户返回 null，限流将跳过（SecurityConfig 已要求 /api/speech/** 需认证）
        log.warn("请求中未找到 userId，可能是未认证请求");
        return null;
    }

    /**
     * IDOR 防护：校验当前用户是否有权访问指定会话。
     *
     * <p>规则：
     * <ul>
     *   <li>会话无归属（user_id 为 NULL，历史数据）→ 放行（兼容）</li>
     *   <li>会话归属为当前用户 → 放行</li>
     *   <li>会话归属为其他用户 → 拒绝</li>
     *   <li>未认证（无 userId）→ 拒绝</li>
     * </ul>
     *
     * @return true 放行 / false 拒绝
     */
    private boolean checkSessionOwnership(String courseId, HttpServletRequest httpRequest) {
        Long userId = extractUserId(httpRequest);
        if (userId == null) {
            log.warn("未认证用户访问会话被拒绝: {}", courseId);
            return false;
        }

        try {
            var session = voiceDatabaseService.getCompleteSessionInfo(courseId);
            if (session == null) {
                // 会话不存在，交由后续 404 处理
                return true;
            }
            if (session.getUserId() == null) {
                // 历史数据无归属，放行
                return true;
            }
            if (!userId.equals(session.getUserId())) {
                log.warn("越权访问被拒绝: 用户 {} 尝试访问会话 {} (归属用户 {})",
                        userId, courseId, session.getUserId());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("会话归属校验失败，会话ID: {}", courseId, e);
            return false;
        }
    }

    /**
     * 计算请求中的总文本长度（用于限流配额）
     */
    private int calculateTotalTextLength(BulkSynthesisRequest request) {
        if (request.getSlides() == null) return 0;
        int total = 0;
        if (request.getTitle() != null) total += request.getTitle().length();
        for (BulkSynthesisRequest.SlideData slide : request.getSlides()) {
            if (slide == null) continue;
            if (slide.getTitle() != null) total += slide.getTitle().length();
            if (slide.getDescription() != null) total += slide.getDescription().length();
            if (slide.getContentPoints() != null) {
                for (String point : slide.getContentPoints()) {
                    if (point != null) total += point.length();
                }
            }
        }
        return total;
    }

    /**
     * 计算请求体的哈希值（用于幂等控制）
     */
    private String computeRequestHash(BulkSynthesisRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getTitle() != null ? request.getTitle() : "");
        sb.append("|");
        if (request.getSlides() != null) {
            for (BulkSynthesisRequest.SlideData slide : request.getSlides()) {
                if (slide != null) {
                    sb.append(slide.getPageNumber()).append(":");
                    sb.append(slide.getTitle() != null ? slide.getTitle() : "").append(":");
                    if (slide.getContentPoints() != null) {
                        sb.append(String.join(",", slide.getContentPoints()));
                    }
                    sb.append(";");
                }
            }
        }
        return BulkSpeechCache.textHash(sb.toString());
    }

}
