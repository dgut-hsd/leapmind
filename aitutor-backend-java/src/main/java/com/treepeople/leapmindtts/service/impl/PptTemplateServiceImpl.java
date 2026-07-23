package com.treepeople.leapmindtts.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.treepeople.leapmindtts.mapper.PptTemplateMapper;
import com.treepeople.leapmindtts.pojo.entity.PptTemplate;
import com.treepeople.leapmindtts.service.PptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PPT模板服务实现类
 */
@Slf4j
@Service
public class PptTemplateServiceImpl extends ServiceImpl<PptTemplateMapper, PptTemplate> implements PptTemplateService {

    @Override
    public List<PptTemplate> listAll(Long userId) {
        log.info("查询模板列表，用户ID: {}", userId);
        QueryWrapper<PptTemplate> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("is_system", 1)
                .or()
                .eq("user_id", userId);
        return baseMapper.selectList(queryWrapper);
    }
}
