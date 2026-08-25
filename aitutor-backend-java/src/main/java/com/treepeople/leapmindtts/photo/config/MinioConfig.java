package com.treepeople.leapmindtts.photo.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置类
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {
    /** MinIO服务地址 */
    private String endpoint;
    /** 访问密钥 */
    private String accessKey;
    /** 密钥 */
    private String secretKey;
    /** 存储桶名称 */
    private String bucketName;

    /**
     * 注册MinIO客户端
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
