package com.treepeople.leapmindtts.photo.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * MinIO 服务接口
 */
public interface MinioService {
    /**
     * 上传图片到MinIO
     * @param file 图片文件
     * @return 图片访问URL
     */
    String uploadImage(MultipartFile file);

    /**
     * 删除MinIO中的文件
     * @param url 文件URL
     */
    void deleteFile(String url);
}