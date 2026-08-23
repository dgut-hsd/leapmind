package com.treepeople.leapmindtts.service.lesson;

import com.treepeople.leapmindtts.pojo.vo.WeakPointsImprovementVO;

/**
 * 薄弱点改善追踪服务接口（轻量实现版：基于 M1 练习答题记录）
 */
public interface WeakPointsImprovementService {

    /**
     * 查询用户薄弱点改善报告
     * <p>
     * 基于 practice_answer_records 练习记录，对比本期与上期正确率，
     * 结合 practice_mistakes 状态生成改善报告。
     *
     * @param userId 用户ID
     * @param period 统计周期：week/month，默认 month
     * @return 改善报告
     */
    WeakPointsImprovementVO getImprovementReport(Long userId, String period);
}
