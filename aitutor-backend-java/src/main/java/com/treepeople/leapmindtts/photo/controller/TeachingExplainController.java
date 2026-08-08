package com.treepeople.leapmindtts.photo.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.treepeople.leapmindtts.photo.dto.PageDTO;
import com.treepeople.leapmindtts.photo.dto.Result;
import com.treepeople.leapmindtts.photo.entity.TeachingContents;
import com.treepeople.leapmindtts.photo.service.MinioService;
import com.treepeople.leapmindtts.photo.service.TeachingContentsService;
import com.treepeople.leapmindtts.photo.utils.ImageValidator;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

/**
 * 讲题记录控制器
 */
@RestController
@RequestMapping("/api/explain")
public class TeachingExplainController {
    @Resource
    private TeachingContentsService teachingContentsService;
    @Resource
    private MinioService minioService;

    /**
     * 新增讲题记录
     * 上传图片到MinIO，保存记录到数据库
     */
    @PostMapping("/save")
    public Result<Long> save(@RequestParam("file") MultipartFile file,
                             @RequestParam Long userId,
                             @RequestParam String questionText) {
        // 1. 校验图片
        ImageValidator.check(file);

        // 2. 上传图片到MinIO
        String imageUrl = minioService.uploadImage(file);

        // 3. 保存到数据库
        TeachingContents entity = new TeachingContents();
        entity.setUserId(userId);
        entity.setImageUrl(imageUrl);
        entity.setQuestionText(questionText);
        entity.setStatus(0); // 0-处理中
        teachingContentsService.save(entity);

        return Result.success(entity.getExplainId());
    }

    /**
     * 查询讲题详情
     */
    @GetMapping("/{explainId}")
    public Result<TeachingContents> getInfo(@PathVariable Long explainId) {
        TeachingContents data = teachingContentsService.getById(explainId);
        return Result.success(data);
    }

    /**
     * 分页查询讲题历史
     */
    @GetMapping("/history")
    public Result<IPage<TeachingContents>> history(@Valid PageDTO pageDTO,
                                                   @RequestParam(required = false) Long userId) {
        IPage<TeachingContents> page = teachingContentsService.getHistory(userId, pageDTO);
        return Result.success(page);
    }

    /**
     * 删除讲题记录
     */
    @DeleteMapping("/{explainId}")
    public Result<Void> delete(@PathVariable Long explainId) {
        teachingContentsService.deleteRecord(explainId);
        return Result.success();
    }
}