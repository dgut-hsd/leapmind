package com.treepeople.leapmindtts.photo.utils;

import com.treepeople.leapmindtts.photo.exception.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 图片校验工具类
 */
public class ImageValidator {
    /** 允许的图片格式 */
    private static final List<String> ALLOW_SUFFIX = List.of("jpg", "jpeg", "png", "bmp");
    /** 最大文件大小：5MB */
    private static final long MAX_SIZE = 5 * 1024 * 1024;

    /**
     * 校验上传的图片
     * @param file 上传的文件
     */
    public static void check(MultipartFile file) {
        // 1. 检查文件是否为空
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请上传图片文件");
        }

        // 2. 检查文件大小
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(400, "图片大小不能超过5MB");
        }

        // 3. 检查文件格式
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            throw new BusinessException(400, "文件名格式不正确");
        }
        String suffix = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        if (!ALLOW_SUFFIX.contains(suffix)) {
            throw new BusinessException(400, "仅支持jpg/jpeg/png/bmp格式图片");
        }
    }
}
