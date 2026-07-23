package com.treepeople.leapmindtts.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PPT模板视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PptTemplateVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 模板名称
     */
    private String name;

    /**
     * 配置JSON
     */
    private String configJson;

    /**
     * 预览图片URL
     */
    private String previewImageUrl;

    /**
     * 是否系统模板（1-是，0-否）
     */
    private Integer isSystem;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
