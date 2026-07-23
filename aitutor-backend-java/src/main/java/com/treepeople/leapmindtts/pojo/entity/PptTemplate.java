package com.treepeople.leapmindtts.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PPT模板实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ppt_templates")
public class PptTemplate {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 模板名称
     */
    @TableField("name")
    private String name;

    /**
     * 配置JSON
     */
    @TableField("config_json")
    private String configJson;

    /**
     * 预览图片URL
     */
    @TableField("preview_image_url")
    private String previewImageUrl;

    /**
     * 是否系统模板（1-是，0-否）
     */
    @TableField("is_system")
    private Integer isSystem;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
