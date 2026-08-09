package com.treepeople.leapmindtts.photo.dto;

import lombok.Data;

import jakarta.validation.constraints.Min;

/**
 * 分页查询参数
 */
@Data
public class PageDTO {
    /** 页码，从1开始 */
    @Min(value = 1, message = "页码最小为1")
    private Long pageNum = 1L;

    /** 每页条数 */
    @Min(value = 1, message = "每页条数最小为1")
    private Long pageSize = 10L;
}