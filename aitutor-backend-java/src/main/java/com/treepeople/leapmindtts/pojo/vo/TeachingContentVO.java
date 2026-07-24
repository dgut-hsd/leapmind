package com.treepeople.leapmindtts.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 备课内容视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeachingContentVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 备课ID
     */
    private Long prepId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 备课标题
     */
    private String title;

    /**
     * 备课状态（draft-草稿, published-已发布, archived-已归档）
     */
    private String status;

    /**
     * PPT结构JSON
     */
    private String pptStructure;

    /**
     * 应用的模板ID
     */
    private Long templateId;

    /**
     * PPT导出下载链接
     */
    private String pptDownloadUrl;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
