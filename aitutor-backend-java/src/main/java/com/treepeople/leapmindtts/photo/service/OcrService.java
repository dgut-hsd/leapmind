package com.treepeople.leapmindtts.photo.service;

import com.treepeople.leapmindtts.photo.dto.OcrResultDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * OCR 文字识别服务接口
 */
public interface OcrService {
    /**
     * 识别图片中的文字
     * @param file 图片文件
     * @return 识别结果
     */
    OcrResultDTO recognize(MultipartFile file);
}
