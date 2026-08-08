package com.treepeople.leapmindtts.photo.service.impl;

import com.treepeople.leapmindtts.photo.config.MinioConfig;
import com.treepeople.leapmindtts.photo.exception.BusinessException;
import com.treepeople.leapmindtts.photo.service.MinioService;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * MinIO 服务实现类
 */
@Service
public class MinioServiceImpl implements MinioService {
    @Resource
    private MinioClient minioClient;
    @Resource
    private MinioConfig minioConfig;

    @Override
    public String uploadImage(MultipartFile file) {
        // 1. 生成文件名：日期路径 + UUID + 后缀
        String suffix = file.getOriginalFilename()
                .substring(file.getOriginalFilename().lastIndexOf("."));
        String datePath = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectName = "explain/" + datePath + "/" + UUID.randomUUID() + suffix;

        // 2. 上传到MinIO
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new BusinessException(500, "图片上传失败：" + e.getMessage());
        }

        // 3. 返回完整访问URL
        return minioConfig.getEndpoint() + "/" + minioConfig.getBucketName() + "/" + objectName;
    }

    @Override
    public void deleteFile(String url) {
        try {
            // 从URL中提取对象路径
            String bucketUrl = minioConfig.getEndpoint() + "/" + minioConfig.getBucketName() + "/";
            String object = url.replace(bucketUrl, "");

            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .object(object)
                    .build());
        } catch (Exception e) {
            throw new BusinessException(500, "文件删除失败：" + e.getMessage());
        }
    }
}