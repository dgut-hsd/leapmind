package com.treepeople.leapmindtts.controller.lesson;

import com.treepeople.leapmindtts.pojo.dto.TeachingContentUpdateDTO;
import com.treepeople.leapmindtts.pojo.entity.TeachingContent;
import com.treepeople.leapmindtts.pojo.result.ApiResponse;
import com.treepeople.leapmindtts.pojo.vo.TeachingContentVO;
import com.treepeople.leapmindtts.service.PptxExportService;
import com.treepeople.leapmindtts.service.TeachingContentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 备课内容控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/lesson-prep/contents")
@RequiredArgsConstructor
@Tag(name = "备课内容", description = "备课内容管理相关接口")
public class TeachingContentController {

    private final TeachingContentService teachingContentService;
    private final PptxExportService pptxExportService;

    /** 草稿 */
    private static final String STATUS_DRAFT = "draft";
    /** 已发布 */
    private static final String STATUS_PUBLISHED = "published";
    /** 已归档 */
    private static final String STATUS_ARCHIVED = "archived";

    /**
     * 获取备课列表
     *
     * @param userId 用户ID
     * @param status 状态筛选（可选）
     * @return 备课列表
     */
    @GetMapping
    @Operation(summary = "获取备课列表", description = "查询当前用户所有备课，按创建时间倒序，可选按状态筛选")
    public ResponseEntity<ApiResponse<List<TeachingContentVO>>> listContents(
            @Parameter(description = "用户ID", required = true)
            @RequestParam Long userId,
            @Parameter(description = "状态筛选（draft/published/archived）")
            @RequestParam(required = false) String status) {
        log.info("查询备课列表，用户ID: {}，状态: {}", userId, status);
        try {
            List<TeachingContent> list = teachingContentService.listByUserId(userId, status);
            List<TeachingContentVO> voList = list.stream()
                    .map(this::convertToVO)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(voList, "查询备课列表成功"));
        } catch (Exception e) {
            log.error("查询备课列表失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * 获取备课详情
     *
     * @param prepId 备课ID
     * @param userId 用户ID
     * @return 备课详情
     */
    @GetMapping("/{prepId}")
    @Operation(summary = "获取备课详情", description = "查询备课详情并校验是否属于当前用户")
    public ResponseEntity<ApiResponse<TeachingContentVO>> getContentDetail(
            @Parameter(description = "备课ID", required = true)
            @PathVariable Long prepId,
            @Parameter(description = "用户ID", required = true)
            @RequestParam Long userId) {
        log.info("查询备课详情，ID: {}，用户ID: {}", prepId, userId);
        try {
            TeachingContent content = teachingContentService.getById(prepId);
            if (content == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "备课内容不存在"));
            }
            if (!content.getUserId().equals(userId)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "无权访问该备课"));
            }
            return ResponseEntity.ok(ApiResponse.success(convertToVO(content), "查询备课详情成功"));
        } catch (Exception e) {
            log.error("查询备课详情失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * 更新备课
     *
     * @param prepId 备课ID
     * @param dto    更新参数
     * @param userId 用户ID
     * @return 操作结果
     */
    @PutMapping("/{prepId}")
    @Operation(summary = "更新备课", description = "更新备课内容或状态，含状态流转校验")
    public ResponseEntity<ApiResponse<Void>> updateContent(
            @Parameter(description = "备课ID", required = true)
            @PathVariable Long prepId,
            @Parameter(description = "更新参数", required = true)
            @RequestBody TeachingContentUpdateDTO dto,
            @Parameter(description = "用户ID", required = true)
            @RequestParam Long userId) {
        log.info("更新备课，ID: {}，用户ID: {}", prepId, userId);
        try {
            // 查询备课是否存在
            TeachingContent content = teachingContentService.getById(prepId);
            if (content == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "备课内容不存在"));
            }

            // 校验是否属于当前用户
            if (!content.getUserId().equals(userId)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "只能修改自己的备课"));
            }

            String currentStatus = content.getStatus();
            String newStatus = dto.getStatus();
            boolean hasContentChange = dto.getTitle() != null || dto.getPptStructure() != null;

            // 状态流转校验
            if (STATUS_DRAFT.equals(currentStatus)) {
                // draft：可以修改内容，可以改为 published
                if (newStatus != null && !newStatus.isBlank()) {
                    if (!STATUS_PUBLISHED.equals(newStatus)) {
                        return ResponseEntity.badRequest()
                                .body(ApiResponse.error(400, "草稿状态只能发布为 published"));
                    }
                    content.setStatus(STATUS_PUBLISHED);
                }
                if (hasContentChange) {
                    if (dto.getTitle() != null) {
                        content.setTitle(dto.getTitle());
                    }
                    if (dto.getPptStructure() != null) {
                        content.setPptStructure(dto.getPptStructure());
                    }
                }
            } else if (STATUS_PUBLISHED.equals(currentStatus)) {
                // published：不能修改内容，只能改为 archived
                if (hasContentChange) {
                    return ResponseEntity.badRequest()
                            .body(ApiResponse.error(400, "已发布状态不能修改内容"));
                }
                if (newStatus != null && !newStatus.isBlank()) {
                    if (!STATUS_ARCHIVED.equals(newStatus)) {
                        return ResponseEntity.badRequest()
                                .body(ApiResponse.error(400, "已发布状态只能归档为 archived"));
                    }
                    content.setStatus(STATUS_ARCHIVED);
                } else {
                    return ResponseEntity.badRequest()
                            .body(ApiResponse.error(400, "已发布状态必须指定目标状态"));
                }
            } else if (STATUS_ARCHIVED.equals(currentStatus)) {
                // archived：不能做任何修改
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "已归档状态不能修改"));
            }

            teachingContentService.updateById(content);
            log.info("备课更新成功，ID: {}", prepId);
            return ResponseEntity.ok(ApiResponse.success(null, "更新备课成功"));
        } catch (Exception e) {
            log.error("更新备课失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * 删除备课
     *
     * @param prepId 备课ID
     * @param userId 用户ID
     * @return 操作结果
     */
    @DeleteMapping("/{prepId}")
    @Operation(summary = "删除备课", description = "仅允许删除草稿状态的备课")
    public ResponseEntity<ApiResponse<Void>> deleteContent(
            @Parameter(description = "备课ID", required = true)
            @PathVariable Long prepId,
            @Parameter(description = "用户ID", required = true)
            @RequestParam Long userId) {
        log.info("删除备课，ID: {}，用户ID: {}", prepId, userId);
        try {
            // 查询备课是否存在
            TeachingContent content = teachingContentService.getById(prepId);
            if (content == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "备课内容不存在"));
            }

            // 校验是否属于当前用户
            if (!content.getUserId().equals(userId)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "只能删除自己的备课"));
            }

            // 只有 draft 状态可以删除
            if (!STATUS_DRAFT.equals(content.getStatus())) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(400, "仅草稿状态的备课可以删除"));
            }

            teachingContentService.removeById(prepId);
            log.info("备课删除成功，ID: {}", prepId);
            return ResponseEntity.ok(ApiResponse.success(null, "删除备课成功"));
        } catch (Exception e) {
            log.error("删除备课失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * 导出PPT
     *
     * @param prepId 备课ID
     * @return 下载URL
     */
    @PostMapping("/{prepId}/export-ppt")
    @Operation(summary = "导出PPT", description = "导出备课内容为PPTX文件并返回下载URL")
    public ResponseEntity<ApiResponse<String>> exportPpt(
            @Parameter(description = "备课ID", required = true)
            @PathVariable Long prepId) {
        log.info("导出PPT，备课ID: {}", prepId);
        try {
            String downloadUrl = pptxExportService.exportPptxByPrepId(prepId);
            log.info("PPT导出成功，备课ID: {}，下载URL: {}", prepId, downloadUrl);
            return ResponseEntity.ok(ApiResponse.success(downloadUrl, "导出PPT成功"));
        } catch (Exception e) {
            log.error("导出PPT失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        }
    }

    /**
     * 将 TeachingContent 实体转换为 TeachingContentVO
     */
    private TeachingContentVO convertToVO(TeachingContent content) {
        return TeachingContentVO.builder()
                .id(content.getId())
                .prepId(content.getPrepId())
                .userId(content.getUserId())
                .title(content.getTitle())
                .status(content.getStatus())
                .pptStructure(content.getPptStructure())
                .templateId(content.getTemplateId())
                .createdAt(content.getCreatedAt())
                .updatedAt(content.getUpdatedAt())
                .build();
    }
}
