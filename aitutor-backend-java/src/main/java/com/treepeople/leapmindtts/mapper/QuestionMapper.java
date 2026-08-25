package com.treepeople.leapmindtts.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 题库 Mapper（M2 题库匹配用，基于 M1 的 practice_questions 表）
 */
@Mapper
public interface QuestionMapper {

    /**
     * 关键词匹配相似题目（M1 practice_questions 表）
     */
    @Select("<script>" +
            "SELECT id, content AS content_json, correct_answer AS answer_json, subject, question_type AS type " +
            "FROM practice_questions " +
            "WHERE status = 'ENABLED' " +
            "<if test='subject != null and subject != \"\"'>" +
            "AND subject = #{subject} " +
            "</if>" +
            "AND (content LIKE CONCAT('%', #{keyword}, '%') OR title LIKE CONCAT('%', #{keyword}, '%')) " +
            "LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> fulltextSearch(@Param("keyword") String keyword,
                                              @Param("subject") String subject,
                                              @Param("limit") int limit);

    /**
     * LIKE 模糊匹配（兜底方案，与 fulltextSearch 同一数据源，保留以兼容调用方）
     */
    @Select("<script>" +
            "SELECT id, content AS content_json, correct_answer AS answer_json, subject, question_type AS type " +
            "FROM practice_questions " +
            "WHERE status = 'ENABLED' " +
            "<if test='subject != null and subject != \"\"'>" +
            "AND subject = #{subject} " +
            "</if>" +
            "AND content LIKE CONCAT('%', #{keyword}, '%') " +
            "LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> likeSearch(@Param("keyword") String keyword,
                                          @Param("subject") String subject,
                                          @Param("limit") int limit);
}
