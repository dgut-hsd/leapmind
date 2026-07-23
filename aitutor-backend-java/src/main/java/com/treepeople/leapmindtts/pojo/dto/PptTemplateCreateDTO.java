package com.treepeople.leapmindtts.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * PPT模板创建请求DTO
 */
@Data
public class PptTemplateCreateDTO {

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
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
