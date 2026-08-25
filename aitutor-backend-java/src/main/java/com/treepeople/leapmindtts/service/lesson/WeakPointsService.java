package com.treepeople.leapmindtts.service.lesson;

import com.treepeople.leapmindtts.pojo.dto.ExerciseRecordRequest;
import com.treepeople.leapmindtts.pojo.dto.PracticePlanRequest;
import com.treepeople.leapmindtts.pojo.result.PageResult;
import com.treepeople.leapmindtts.pojo.vo.ExerciseVO;
import com.treepeople.leapmindtts.pojo.vo.KnowledgeGraphVO;
import com.treepeople.leapmindtts.pojo.vo.PracticePlanVO;
import com.treepeople.leapmindtts.pojo.vo.RecommendQuestionVO;
import com.treepeople.leapmindtts.pojo.vo.UserWeakPointVO;
import com.treepeople.leapmindtts.pojo.vo.WeakPointDetailVO;
import com.treepeople.leapmindtts.pojo.vo.WeakPointsAnalysisVO;

import java.util.List;

/**
 * 薄弱点分析服务接口（轻量实现版：数据源基于 M1 练习模块的错题本与题库）
 */
public interface WeakPointsService {

    /**
     * 查询用户薄弱点列表（分页）
     */
    PageResult<UserWeakPointVO> getUserWeakPoints(Long userId, String subject, String status, Integer page, Integer size);

    /**
     * 获取/触发 AI 综合分析
     */
    WeakPointsAnalysisVO getOrCreateAnalysis(Long userId);

    /**
     * 推荐练习题（含去重和优先级逻辑）
     */
    List<ExerciseVO> recommendExercises(Long userId, String subject, String knowledgePoint, Integer count);

    /**
     * 记录练习结果
     */
    void recordExerciseResult(ExerciseRecordRequest request);

    /**
     * 根据知识点名称推荐具体题目（薄弱点详情页）
     */
    List<RecommendQuestionVO> recommendQuestions(Long userId, String knowledgePoint, Integer count);

    /**
     * 获取用户知识图谱
     */
    KnowledgeGraphVO getKnowledgeGraph(Long userId, String subject);

    /**
     * 获取单个薄弱点详情
     */
    WeakPointDetailVO getWeakPointDetail(Long id);

    /**
     * 根据薄弱点生成练习计划
     */
    PracticePlanVO generatePracticePlan(PracticePlanRequest request);
}
