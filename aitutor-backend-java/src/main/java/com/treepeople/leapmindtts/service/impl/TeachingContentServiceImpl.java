package com.treepeople.leapmindtts.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.treepeople.leapmindtts.mapper.TeachingContentMapper;
import com.treepeople.leapmindtts.pojo.entity.TeachingContent;
import com.treepeople.leapmindtts.service.TeachingContentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 备课内容服务实现类
 */
@Slf4j
@Service
public class TeachingContentServiceImpl extends ServiceImpl<TeachingContentMapper, TeachingContent> implements TeachingContentService {

    @Override
    public List<TeachingContent> listByUserId(Long userId, String status) {
        log.info("查询备课列表，用户ID: {}，状态: {}", userId, status);
        QueryWrapper<TeachingContent> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        if (status != null && !status.isBlank()) {
            queryWrapper.eq("status", status);
        }
        queryWrapper.orderByDesc("created_at");
        return baseMapper.selectList(queryWrapper);
    }
}
