package com.treepeople.leapmindtts.service.lesson;

import com.treepeople.leapmindtts.pojo.dto.ExerciseRecordRequest;
import com.treepeople.leapmindtts.pojo.vo.ExerciseVO;
import com.treepeople.leapmindtts.pojo.vo.UserWeakPointVO;
import com.treepeople.leapmindtts.pojo.vo.WeakPointsAnalysisVO;

import java.util.List;

/**
 * 薄弱点分析服务接口
 */
public interface WeakPointsService {

    /**
     * 查询用户薄弱点列表
     *
     * @param userId  用户ID
     * @param subject 学科（可选）
     * @param status  状态过滤（可选）
     * @return 薄弱点列表
     */
    List<UserWeakPointVO> getUserWeakPoints(Long userId, String subject, String status);

    /**
     * 获取/触发 AI 综合分析
     * 如果已有近期分析则直接返回，否则调用 Python AI 服务
     *
     * @param userId 用户ID
     * @return AI 分析结果
     */
    WeakPointsAnalysisVO getOrCreateAnalysis(Long userId);

    /**
     * 推荐练习题（含去重和优先级逻辑）
     *
     * @param userId         用户ID
     * @param subject        学科（可选）
     * @param knowledgePoint 知识点（可选）
     * @param count          推荐数量
     * @return 推荐练习列表
     */
    List<ExerciseVO> recommendExercises(Long userId, String subject, String knowledgePoint, Integer count);

    /**
     * 记录练习结果
     * 同时更新薄弱点状态（如果是错题则增加 error_count，全对则标记 RESOLVED）
     *
     * @param request 练习记录请求
     */
    void recordExerciseResult(ExerciseRecordRequest request);
}
