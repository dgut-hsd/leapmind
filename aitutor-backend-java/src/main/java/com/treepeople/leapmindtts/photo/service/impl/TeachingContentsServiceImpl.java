package com.treepeople.leapmindtts.photo.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.treepeople.leapmindtts.photo.dto.PageDTO;
import com.treepeople.leapmindtts.photo.entity.TeachingContents;
import com.treepeople.leapmindtts.photo.exception.BusinessException;
import com.treepeople.leapmindtts.photo.mapper.TeachingContentsMapper;
import com.treepeople.leapmindtts.photo.service.MinioService;
import com.treepeople.leapmindtts.photo.service.TeachingContentsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

/**
 * 讲题记录服务实现类
 */
@Service
public class TeachingContentsServiceImpl extends ServiceImpl<TeachingContentsMapper, TeachingContents>
        implements TeachingContentsService {
    @Resource
    private MinioService minioService;

    @Override
    public IPage<TeachingContents> getHistory(Long userId, PageDTO pageDTO) {
        Page<TeachingContents> page = new Page<>(pageDTO.getPageNum(), pageDTO.getPageSize());
        return this.page(page, Wrappers.<TeachingContents>lambdaQuery()
                .eq(userId != null, TeachingContents::getUserId, userId)
                .orderByDesc(TeachingContents::getCreateTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(Long explainId) {
        // 1. 查询记录是否存在
        TeachingContents record = this.getById(explainId);
        if (record == null) {
            throw new BusinessException(400, "记录不存在");
        }

        // 2. 逻辑删除数据库记录
        this.removeById(explainId);

        // 3. 同时删除MinIO中的图片
        if (record.getImageUrl() != null && !record.getImageUrl().isEmpty()) {
            minioService.deleteFile(record.getImageUrl());
        }
    }
}