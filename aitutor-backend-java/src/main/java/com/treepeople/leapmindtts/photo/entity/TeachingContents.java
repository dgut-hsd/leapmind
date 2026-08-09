package com.treepeople.leapmindtts.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 讲题记录实体类
 * 对应数据库表：teaching_contents
 */
@Data
@TableName("teaching_contents")
public class TeachingContents {
    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long explainId;

    /** 用户ID */
    private Long userId;

    /** 题目图片地址 */
    private String imageUrl;

    /** OCR识别题目文本 */
    private String questionText;

    /** AI答案 */
    private String aiAnswer;

    /** AI解题讲解 */
    private String aiExplain;

    /** 状态：0处理中 1完成 2识别失败 */
    private Integer status;

    /** 逻辑删除：0未删 1已删 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}