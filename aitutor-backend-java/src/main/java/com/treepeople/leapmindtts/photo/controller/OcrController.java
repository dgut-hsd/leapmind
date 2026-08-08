package com.treepeople.leapmindtts.photo.controller;

import com.treepeople.leapmindtts.photo.dto.OcrResultDTO;
import com.treepeople.leapmindtts.photo.dto.Result;
import com.treepeople.leapmindtts.photo.service.OcrService;
import com.treepeople.leapmindtts.photo.utils.ImageValidator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;

/**
 * OCR 文字识别控制器
 */
@RestController
@RequestMapping("/api/ocr")
public class OcrController {
    @Resource
    private OcrService ocrService;

    /**
     * 拍照识别接口
     * @param file 题目图片
     * @return 识别出的文字内容
     */
    @PostMapping("/recognize")
    public Result<OcrResultDTO> recognize(@RequestParam("file") MultipartFile file) {
        // 校验图片
        ImageValidator.check(file);
        // 调用OCR识别
        OcrResultDTO result = ocrService.recognize(file);
        return Result.success(result);
    }
}