package com.treepeople.leapmindtts.controller.virtualteacher;

import com.treepeople.leapmindtts.annotation.AdminRequired;
import com.treepeople.leapmindtts.exception.UnauthorizedException;
import com.treepeople.leapmindtts.pojo.dto.TeacherAvatarRequest;
import com.treepeople.leapmindtts.pojo.dto.TeacherPreferenceRequest;
import com.treepeople.leapmindtts.pojo.dto.VirtualTeacherTtsRequest;
import com.treepeople.leapmindtts.pojo.result.ApiResponse;
import com.treepeople.leapmindtts.pojo.vo.TeacherAvatarVO;
import com.treepeople.leapmindtts.pojo.vo.VirtualTeacherTtsVO;
import com.treepeople.leapmindtts.service.virtualteacher.TeacherAvatarService;
import com.treepeople.leapmindtts.service.virtualteacher.VirtualTeacherTtsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/virtual-teacher")
@RequiredArgsConstructor
public class VirtualTeacherController {
    private final TeacherAvatarService avatarService;
    private final VirtualTeacherTtsService ttsService;

    @GetMapping("/avatars")
    public ApiResponse<List<TeacherAvatarVO>> listAvatars() {
        return ApiResponse.success(avatarService.listEnabled());
    }

    @GetMapping("/preference")
    public ApiResponse<TeacherAvatarVO> getPreference(HttpServletRequest request) {
        return ApiResponse.success(avatarService.getPreference(requireUserId(request)));
    }

    @PutMapping("/preference")
    public ApiResponse<TeacherAvatarVO> savePreference(
            HttpServletRequest httpRequest,
            @Valid @RequestBody TeacherPreferenceRequest request) {
        return ApiResponse.success(
                avatarService.savePreference(requireUserId(httpRequest), request),
                "虚拟教师偏好已保存");
    }

    @GetMapping("/avatars/admin")
    @AdminRequired
    public ApiResponse<List<TeacherAvatarVO>> listAllAvatars() {
        return ApiResponse.success(avatarService.listAll());
    }

    @PostMapping("/avatars")
    @AdminRequired
    public ApiResponse<TeacherAvatarVO> createAvatar(
            @Valid @RequestBody TeacherAvatarRequest request) {
        return ApiResponse.success(avatarService.create(request), "教师形象已创建");
    }

    @PutMapping("/avatars/{id}")
    @AdminRequired
    public ApiResponse<TeacherAvatarVO> updateAvatar(
            @PathVariable Long id,
            @Valid @RequestBody TeacherAvatarRequest request) {
        return ApiResponse.success(avatarService.update(id, request), "教师形象已更新");
    }

    @DeleteMapping("/avatars/{id}")
    @AdminRequired
    public ApiResponse<Void> deleteAvatar(@PathVariable Long id) {
        avatarService.delete(id);
        return ApiResponse.success(null, "教师形象已删除");
    }

    @PostMapping("/tts")
    public ApiResponse<VirtualTeacherTtsVO> synthesize(
            HttpServletRequest httpRequest,
            @Valid @RequestBody VirtualTeacherTtsRequest request) {
        return ApiResponse.success(ttsService.synthesize(requireUserId(httpRequest), request).response(), "语音合成完成");
    }

    @PostMapping(value = "/tts/stream")
    public ResponseEntity<StreamingResponseBody> synthesizeStream(
            HttpServletRequest httpRequest,
            @Valid @RequestBody VirtualTeacherTtsRequest request) {
        Long userId = requireUserId(httpRequest);
        VirtualTeacherTtsService.StreamingPlan plan = ttsService.stream(userId, request);
        StreamingResponseBody body = output -> ttsService.writeStream(plan, output, userId, request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .header("X-Audio-Format", "pcm-s16")
                .header("X-Audio-Sample-Rate", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Order", "little-endian")
                .body(body);
    }

    @GetMapping(value = "/audio/{objectKey:.+}", produces = "audio/wav")
    public ResponseEntity<byte[]> getAudio(@PathVariable String objectKey) {
        return ttsService.loadAudio(objectKey)
                .map(audio -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(24, TimeUnit.HOURS).cachePublic())
                        .contentType(MediaType.parseMediaType(VirtualTeacherTtsService.AUDIO_CONTENT_TYPE))
                        .body(audio))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Long requireUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId instanceof Long value) return value;
        throw new UnauthorizedException("用户未登录");
    }
}
