package com.treepeople.leapmindtts.pojo.dto;

import lombok.Data;

/**
 * 备课内容更新请求DTO
 */
@Data
public class TeachingContentUpdateDTO {

    /**
     * 备课标题
     */
    private String title;

    /**
     * PPT结构JSON
     */
    private String pptStructure;

    /**
     * 备课状态（draft-草稿, published-已发布, archived-已归档）
     */
    private String status;
}
