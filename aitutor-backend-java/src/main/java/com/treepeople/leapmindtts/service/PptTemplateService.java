package com.treepeople.leapmindtts.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.treepeople.leapmindtts.pojo.entity.PptTemplate;

import java.util.List;

/**
 * PPT模板服务接口
 */
public interface PptTemplateService extends IService<PptTemplate> {

    /**
     * 查询系统模板 + 用户自己的模板
     *
     * @param userId 用户ID
     * @return 模板列表
     */
    List<PptTemplate> listAll(Long userId);
}
