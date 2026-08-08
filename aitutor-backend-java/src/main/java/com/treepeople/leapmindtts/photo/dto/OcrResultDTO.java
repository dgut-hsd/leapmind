package com.treepeople.leapmindtts.photo.dto;

import lombok.Data;

/**
 * OCR识别结果
 */
@Data
public class OcrResultDTO {
    /** 识别出的完整文字 */
    private String text;
    /** 识别出的行数 */
    private Integer wordsCount;
    /** 图片方向（0-正常） */
    private Integer direction;
    /** 使用的OCR服务商 */
    private String provider;
    /** 识别耗时（毫秒） */
    private Long costTime;
}