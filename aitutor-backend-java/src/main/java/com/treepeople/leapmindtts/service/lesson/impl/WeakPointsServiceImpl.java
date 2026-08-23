package com.treepeople.leapmindtts.service.lesson.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.treepeople.leapmindtts.mapper.PracticeAnswerRecordMapper;
import com.treepeople.leapmindtts.mapper.PracticeMistakeMapper;
import com.treepeople.leapmindtts.mapper.PracticeQuestionMapper;
import com.treepeople.leapmindtts.pojo.dto.ExerciseRecordRequest;
import com.treepeople.leapmindtts.pojo.dto.PracticePlanRequest;
import com.treepeople.leapmindtts.pojo.entity.PracticeAnswerRecord;
import com.treepeople.leapmindtts.pojo.entity.PracticeMistake;
import com.treepeople.leapmindtts.pojo.entity.PracticeQuestion;
import com.treepeople.leapmindtts.pojo.result.PageResult;
import com.treepeople.leapmindtts.pojo.vo.ExerciseVO;
import com.treepeople.leapmindtts.pojo.vo.KnowledgeGraphVO;
import com.treepeople.leapmindtts.pojo.vo.PracticePlanVO;
import com.treepeople.leapmindtts.pojo.vo.RecommendQuestionVO;
import com.treepeople.leapmindtts.pojo.vo.UserWeakPointVO;
import com.treepeople.leapmindtts.pojo.vo.WeakPointDetailVO;
import com.treepeople.leapmindtts.pojo.vo.WeakPointsAnalysisVO;
import com.treepeople.leapmindtts.service.lesson.WeakPointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 薄弱点服务轻量实现（最小移植版）。
 * 数据源基于 M1 自有的 practice_questions / practice_mistakes / practice_answer_records 表，
 * 未引入独立的 user_exercises / user_weak_points 弱项模块与 Python M3 引擎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeakPointsServiceImpl implements WeakPointsService {

    private static final String STATUS_UNRESOLVED = "UNRESOLVED";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_ENABLED = "ENABLED";

    private final PracticeQuestionMapper questionMapper;
    private final PracticeMistakeMapper mistakeMapper;
    private final PracticeAnswerRecordMapper recordMapper;

    @Override
    public PageResult<UserWeakPointVO> getUserWeakPoints(Long userId, String subject, String status, Integer page, Integer size) {
        int p = page == null ? 1 : Math.max(1, page);
        int s = size == null ? 20 : Math.max(1, Math.min(100, size));

        List<PracticeMistake> mistakes = mistakeMapper.selectList(new QueryWrapper<PracticeMistake>()
                .eq("user_id", userId)
                .orderByDesc("last_wrong_at"));
        if (mistakes.isEmpty()) {
            return PageResult.<UserWeakPointVO>builder().total(0L).pages(0L)
                    .current((long) p).size((long) s).records(List.of()).build();
        }

        Set<Long> qids = mistakes.stream().map(PracticeMistake::getQuestionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PracticeQuestion> questionMap = qids.isEmpty() ? Map.of()
                : questionMapper.selectBatchIds(qids).stream()
                        .collect(Collectors.toMap(PracticeQuestion::getId, q -> q, (a, b) -> a));

        // 该用户全部答题记录（用于知识点正确率统计）
        Map<String, long[]> kpStats = new HashMap<>();
        List<PracticeAnswerRecord> records = recordMapper.selectList(
                new QueryWrapper<PracticeAnswerRecord>().eq("user_id", userId));
        for (PracticeAnswerRecord r : records) {
            if (r.getKnowledgePoint() == null) continue;
            long[] stat = kpStats.computeIfAbsent(r.getKnowledgePoint(), k -> new long[2]);
            stat[0]++;
            if (Boolean.TRUE.equals(r.getCorrect())) stat[1]++;
        }

        // 按知识点聚合错题
        Map<String, List<PracticeMistake>> byKp = new LinkedHashMap<>();
        for (PracticeMistake m : mistakes) {
            PracticeQuestion q = questionMap.get(m.getQuestionId());
            if (q == null) continue;
            if (subject != null && !subject.isBlank() && !subject.equals(q.getSubject())) continue;
            String st = normalizeStatus(m.getStatus());
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(st)) continue;
            byKp.computeIfAbsent(q.getKnowledgePoint(), k -> new ArrayList<>()).add(m);
        }

        List<UserWeakPointVO> all = new ArrayList<>();
        for (Map.Entry<String, List<PracticeMistake>> entry : byKp.entrySet()) {
            List<PracticeMistake> ms = entry.getValue();
            PracticeQuestion q0 = questionMap.get(ms.get(0).getQuestionId());
            boolean anyActive = ms.stream().anyMatch(m -> STATUS_UNRESOLVED.equals(normalizeStatus(m.getStatus())));
            int wrong = ms.stream().mapToInt(m -> nvl(m.getWrongCount())).sum();
            int resolved = (int) ms.stream().filter(m -> STATUS_RESOLVED.equals(normalizeStatus(m.getStatus()))).count();
            LocalDateTime last = ms.stream().map(PracticeMistake::getLastWrongAt)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            long[] stat = kpStats.getOrDefault(entry.getKey(), new long[]{0, 0});
            BigDecimal accuracy = stat[0] == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(stat[1] * 100.0 / stat[0]).setScale(1, RoundingMode.HALF_UP);
            all.add(UserWeakPointVO.builder()
                    .id(ms.get(0).getId())
                    .userId(userId)
                    .knowledgePoint(entry.getKey())
                    .subject(q0.getSubject())
                    .weaknessLevel(anyActive ? "HIGH" : "MEDIUM")
                    .weaknessScore(BigDecimal.valueOf(anyActive ? 0.8 : 0.4).setScale(2))
                    .errorCount(wrong)
                    .totalCount(wrong + resolved)
                    .accuracyRate(accuracy)
                    .errorRate(BigDecimal.valueOf(100.0 - accuracy.doubleValue()).setScale(1))
                    .recentCorrectRate(accuracy)
                    .confusionCount(0)
                    .trend(anyActive ? "declining" : "improving")
                    .lastErrorTime(last)
                    .status(anyActive ? "ACTIVE" : "RESOLVED")
                    .createdAt(ms.get(0).getCreatedAt())
                    .build());
        }

        int from = (p - 1) * s;
        List<UserWeakPointVO> pageList = from >= all.size() ? List.of()
                : all.subList(from, Math.min(from + s, all.size()));
        return PageResult.<UserWeakPointVO>builder()
                .total((long) all.size())
                .pages((long) Math.ceil(all.size() / (double) s))
                .current((long) p).size((long) s)
                .records(pageList).build();
    }

    @Override
    public WeakPointsAnalysisVO getOrCreateAnalysis(Long userId) {
        PageResult<UserWeakPointVO> weakPoints = getUserWeakPoints(userId, null, null, 1, 50);
        List<UserWeakPointVO> list = weakPoints.getRecords();
        StringBuilder analysis = new StringBuilder();
        StringBuilder suggestions = new StringBuilder();
        List<String> priority = new ArrayList<>();
        List<WeakPointsAnalysisVO.DetailItem> details = new ArrayList<>();
        if (list.isEmpty()) {
            analysis.append("暂无薄弱点数据，请先完成练习后再查看分析。");
            suggestions.append("建议先通过练习模块完成几组题目，系统将自动生成薄弱点分析。");
        } else {
            analysis.append("共发现 ").append(list.size()).append(" 个薄弱知识点。");
            for (UserWeakPointVO v : list) {
                details.add(WeakPointsAnalysisVO.DetailItem.builder()
                        .knowledgePoint(v.getKnowledgePoint())
                        .analysis("该知识点共答错 " + nvl(v.getErrorCount()) + " 次，正确率 " + v.getAccuracyRate() + "%"
                                + (v.getErrorCount() >= 3 ? "，错误频率较高，建议重点复习。" : "，建议加强巩固。"))
                        .suggestion("针对「" + v.getKnowledgePoint() + "」进行针对性练习，并复习相关章节概念。")
                        .build());
                if ("HIGH".equals(v.getWeaknessLevel())) {
                    priority.add(v.getKnowledgePoint());
                }
            }
            suggestions.append("建议优先攻克 ").append(priority.isEmpty() ? "高频错题" : String.join("、", priority)).append(" 等薄弱点。");
        }
        return WeakPointsAnalysisVO.builder()
                .comprehensiveAnalysis(analysis.toString())
                .learningSuggestions(suggestions.toString())
                .detailAnalyses(details)
                .recommendedPriority(priority)
                .build();
    }

    @Override
    public List<ExerciseVO> recommendExercises(Long userId, String subject, String knowledgePoint, Integer count) {
        int limit = count == null ? 5 : Math.max(1, Math.min(20, count));
        Set<Long> seen = new LinkedHashSet<>();
        List<ExerciseVO> result = new ArrayList<>();

        // 1. 优先推荐错题本（UNRESOLVED）关联题目
        List<PracticeMistake> mistakes = mistakeMapper.selectList(new QueryWrapper<PracticeMistake>()
                .eq("user_id", userId)
                .eq("status", STATUS_UNRESOLVED)
                .orderByDesc("last_wrong_at")
                .last("LIMIT 50"));
        for (PracticeMistake mistake : mistakes) {
            if (result.size() >= limit) break;
            PracticeQuestion question = questionMapper.selectById(mistake.getQuestionId());
            if (question == null || seen.contains(question.getId())) continue;
            if (!matches(question, subject, knowledgePoint)) continue;
            seen.add(question.getId());
            result.add(toExerciseVO(question, "ACTIVE_WEAK_POINT", 1));
        }

        // 2. 兜底：同条件下从启用题库补足
        if (result.size() < limit) {
            QueryWrapper<PracticeQuestion> wrapper = new QueryWrapper<>();
            wrapper.eq("status", STATUS_ENABLED);
            if (subject != null && !subject.isBlank()) wrapper.eq("subject", subject);
            if (knowledgePoint != null && !knowledgePoint.isBlank()) wrapper.eq("knowledge_point", knowledgePoint);
            wrapper.last("LIMIT " + (limit * 2));
            for (PracticeQuestion question : questionMapper.selectList(wrapper)) {
                if (result.size() >= limit) break;
                if (seen.contains(question.getId())) continue;
                seen.add(question.getId());
                result.add(toExerciseVO(question, "RECOMMENDED", 2));
            }
        }
        return result;
    }

    @Override
    public void recordExerciseResult(ExerciseRecordRequest request) {
        // M1 答题结果已由 PracticeServiceImpl 写入错题本（practice_mistakes）；
        // 独立弱项模块（user_exercises）未随 M1 移植，此处保持幂等占位，不影响主流程。
        log.debug("WeakPoints recordExerciseResult(no-op): userId={}, exerciseId={}, isCorrect={}",
                request.getUserId(), request.getExerciseId(), request.getIsCorrect());
    }

    @Override
    public List<RecommendQuestionVO> recommendQuestions(Long userId, String knowledgePoint, Integer count) {
        int limit = count == null ? 5 : Math.max(1, Math.min(20, count));
        QueryWrapper<PracticeQuestion> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_ENABLED);
        if (knowledgePoint != null && !knowledgePoint.isBlank()) {
            wrapper.eq("knowledge_point", knowledgePoint);
        }
        wrapper.last("LIMIT " + limit);
        List<RecommendQuestionVO> result = new ArrayList<>();
        for (PracticeQuestion q : questionMapper.selectList(wrapper)) {
            result.add(RecommendQuestionVO.builder()
                    .questionId(String.valueOf(q.getId()))
                    .knowledgePoint(q.getKnowledgePoint())
                    .subject(q.getSubject())
                    .difficulty(q.getDifficulty())
                    .questionType(q.getQuestionType())
                    .questionTitle(q.getTitle() != null ? q.getTitle() : truncate(q.getContent(), 60))
                    .reason("基于薄弱知识点「" + q.getKnowledgePoint() + "」推荐")
                    .build());
        }
        return result;
    }

    @Override
    public KnowledgeGraphVO getKnowledgeGraph(Long userId, String subject) {
        QueryWrapper<PracticeQuestion> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_ENABLED);
        if (subject != null && !subject.isBlank()) wrapper.eq("subject", subject);
        List<PracticeQuestion> questions = questionMapper.selectList(wrapper.last("LIMIT 500"));

        Map<String, String> kpSubject = new LinkedHashMap<>();
        Map<String, String> kpChapter = new LinkedHashMap<>();
        for (PracticeQuestion q : questions) {
            if (q.getKnowledgePoint() == null) continue;
            kpSubject.putIfAbsent(q.getKnowledgePoint(), q.getSubject());
            kpChapter.putIfAbsent(q.getKnowledgePoint(), q.getChapter());
        }

        // 薄弱知识点集合（该用户的错题知识点）
        Set<String> weakKps = mistakeMapper.selectList(new QueryWrapper<PracticeMistake>().eq("user_id", userId))
                .stream().map(m -> {
                    PracticeQuestion q = questionMapper.selectById(m.getQuestionId());
                    return q == null ? null : q.getKnowledgePoint();
                }).filter(Objects::nonNull).collect(Collectors.toSet());

        List<KnowledgeGraphVO.GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : kpSubject.entrySet()) {
            nodes.add(KnowledgeGraphVO.GraphNode.builder()
                    .id(e.getKey())
                    .name(e.getKey())
                    .subject(e.getValue())
                    .weaknessLevel(weakKps.contains(e.getKey()) ? "HIGH" : "MASTERED")
                    .masteryRate(weakKps.contains(e.getKey()) ? 40.0 : 90.0)
                    .build());
        }

        List<KnowledgeGraphVO.GraphEdge> edges = new ArrayList<>();
        Map<String, List<String>> byChapter = kpChapter.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        for (List<String> kps : byChapter.values()) {
            for (int i = 0; i < kps.size() && kps.size() > 1; i++) {
                for (int j = i + 1; j < kps.size(); j++) {
                    edges.add(KnowledgeGraphVO.GraphEdge.builder()
                            .source(kps.get(i)).target(kps.get(j)).relation("related").build());
                }
            }
        }
        return KnowledgeGraphVO.builder().nodes(nodes).edges(edges).build();
    }

    @Override
    public WeakPointDetailVO getWeakPointDetail(Long id) {
        PracticeMistake mistake = mistakeMapper.selectById(id);
        if (mistake == null) {
            throw new IllegalArgumentException("薄弱点记录不存在: " + id);
        }
        PracticeQuestion question = mistake.getQuestionId() == null ? null
                : questionMapper.selectById(mistake.getQuestionId());
        String kp = question == null ? null : question.getKnowledgePoint();
        boolean active = STATUS_UNRESOLVED.equals(normalizeStatus(mistake.getStatus()));

        // 近期错题（该用户该知识点最近错误记录）
        List<WeakPointDetailVO.ErrorExerciseItem> recentErrors = new ArrayList<>();
        if (kp != null) {
            List<PracticeAnswerRecord> wrongRecords = recordMapper.selectList(new QueryWrapper<PracticeAnswerRecord>()
                    .eq("user_id", mistake.getUserId())
                    .eq("knowledge_point", kp)
                    .eq("correct", false)
                    .orderByDesc("answered_at")
                    .last("LIMIT 10"));
            for (PracticeAnswerRecord r : wrongRecords) {
                recentErrors.add(WeakPointDetailVO.ErrorExerciseItem.builder()
                        .id(r.getId())
                        .exerciseId(String.valueOf(r.getQuestionId()))
                        .isCorrect(0)
                        .completedAt(r.getAnsweredAt())
                        .build());
            }
        }

        return WeakPointDetailVO.builder()
                .id(mistake.getId())
                .userId(mistake.getUserId())
                .knowledgePoint(kp)
                .subject(question == null ? null : question.getSubject())
                .weaknessLevel(active ? "HIGH" : "MEDIUM")
                .weaknessScore(BigDecimal.valueOf(active ? 0.8 : 0.4).setScale(2))
                .errorCount(nvl(mistake.getWrongCount()))
                .totalCount(nvl(mistake.getWrongCount()) + nvl(mistake.getReviewCount()))
                .status(active ? "ACTIVE" : "RESOLVED")
                .aiAnalysis(active ? "该知识点错误次数较多，建议针对性复习后重新练习。" : "该知识点已解决，建议定期复习保持掌握。")
                .aiSuggestion("回顾相关章节概念，并完成 3-5 道同知识点题目巩固。")
                .trend(active ? "declining" : "improving")
                .lastErrorTime(mistake.getLastWrongAt())
                .createdAt(mistake.getCreatedAt())
                .recentErrors(recentErrors)
                .build();
    }

    @Override
    public PracticePlanVO generatePracticePlan(PracticePlanRequest request) {
        List<String> kps = request.getKnowledgePoints() == null ? List.of() : request.getKnowledgePoints();
        List<PracticePlanVO.PlanQuestionItem> items = new ArrayList<>();
        int order = 1;
        for (String kp : kps) {
            if (kp == null || kp.isBlank()) continue;
            QueryWrapper<PracticeQuestion> wrapper = new QueryWrapper<>();
            wrapper.eq("status", STATUS_ENABLED).eq("knowledge_point", kp).last("LIMIT 3");
            for (PracticeQuestion q : questionMapper.selectList(wrapper)) {
                items.add(PracticePlanVO.PlanQuestionItem.builder()
                        .questionId(String.valueOf(q.getId()))
                        .knowledgePoint(kp)
                        .subject(q.getSubject())
                        .difficulty(mapDifficulty(q.getDifficulty()))
                        .questionType(q.getQuestionType())
                        .questionTitle(q.getTitle())
                        .order(order++)
                        .reason("针对薄弱知识点「" + kp + "」的巩固练习")
                        .build());
            }
        }
        return PracticePlanVO.builder()
                .userId(request.getUserId())
                .targetKnowledgePoints(kps)
                .totalQuestions(items.size())
                .estimatedMinutes(Math.max(1, items.size() * 2))
                .questions(items)
                .build();
    }

    // ==================== 内部工具 ====================

    private String normalizeStatus(String status) {
        if (status == null) return STATUS_UNRESOLVED;
        String s = status.toUpperCase();
        return s.contains("RESOLVED") ? STATUS_RESOLVED : STATUS_UNRESOLVED;
    }

    private boolean matches(PracticeQuestion question, String subject, String knowledgePoint) {
        if (subject != null && !subject.isBlank() && !subject.equals(question.getSubject())) return false;
        return knowledgePoint == null || knowledgePoint.isBlank() || knowledgePoint.equals(question.getKnowledgePoint());
    }

    private ExerciseVO toExerciseVO(PracticeQuestion question, String sourceType, Integer priority) {
        return ExerciseVO.builder()
                .exerciseId(String.valueOf(question.getId()))
                .knowledgePoint(question.getKnowledgePoint())
                .subject(question.getSubject())
                .sourceType(sourceType)
                .priority(priority)
                .build();
    }

    private String mapDifficulty(String difficulty) {
        if (difficulty == null) return "MEDIUM";
        return switch (difficulty.toUpperCase()) {
            case "BASIC" -> "EASY";
            case "HARD" -> "HARD";
            default -> "MEDIUM";
        };
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
