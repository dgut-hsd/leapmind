package com.treepeople.leapmindtts.photo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OCR 配置类
 * 支持百度OCR和阿里云OCR两种方式
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ocr")
public class OcrConfig {
    /** 使用的OCR服务商：baidu / aliyun */
    private String provider;

    /** 百度OCR配置 */
    private BaiduOcr baidu;

    /** 阿里云OCR配置 */
    private AliOcr aliyun;

    @Data
    public static class BaiduOcr {
        private String appId;
        private String apiKey;
        private String secretKey;
    }

    @Data
    public static class AliOcr {
        /** API市场AppCode */
        private String appCode;
        /** API接口地址 */
        private String apiUrl;
    }
}