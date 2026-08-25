package com.treepeople.leapmindtts.photo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.treepeople.leapmindtts.photo.entity.TeachingContents;
import org.apache.ibatis.annotations.Mapper;

/**
 * 讲题记录 Mapper 接口
 * 继承BaseMapper，自带CRUD方法
 */
@Mapper
public interface TeachingContentsMapper extends BaseMapper<TeachingContents> {
}
