package com.treepeople.leapmindtts.photo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.treepeople.leapmindtts.photo.dto.PageDTO;
import com.treepeople.leapmindtts.photo.entity.TeachingContents;

/**
 * 讲题记录服务接口
 */
public interface TeachingContentsService extends IService<TeachingContents> {
    /**
     * 分页查询讲题历史
     * @param userId 用户ID（可选）
     * @param pageDTO 分页参数
     * @return 分页结果
     */
    IPage<TeachingContents> getHistory(Long userId, PageDTO pageDTO);

    /**
     * 删除讲题记录（同时删除MinIO图片）
     * @param explainId 记录ID
     */
    void deleteRecord(Long explainId);
}
