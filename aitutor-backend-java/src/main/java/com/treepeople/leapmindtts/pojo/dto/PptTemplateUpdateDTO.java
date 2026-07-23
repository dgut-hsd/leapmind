package com.treepeople.leapmindtts.pojo.dto;

import lombok.Data;

/**
 * PPT模板更新请求DTO
 */
@Data
public class PptTemplateUpdateDTO {

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
}
