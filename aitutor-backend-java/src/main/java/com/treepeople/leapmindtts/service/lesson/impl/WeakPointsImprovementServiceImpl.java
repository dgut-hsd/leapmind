package com.treepeople.leapmindtts.service.lesson.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.treepeople.leapmindtts.mapper.PracticeAnswerRecordMapper;
import com.treepeople.leapmindtts.mapper.PracticeMistakeMapper;
import com.treepeople.leapmindtts.pojo.entity.PracticeAnswerRecord;
import com.treepeople.leapmindtts.pojo.vo.WeakPointsImprovementVO;
import com.treepeople.leapmindtts.service.lesson.WeakPointsImprovementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 薄弱点改善报告轻量实现。
 * 数据源：practice_answer_records（答题记录）+ practice_mistakes（错题本）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeakPointsImprovementServiceImpl implements WeakPointsImprovementService {

    private final PracticeAnswerRecordMapper recordMapper;
    private final PracticeMistakeMapper mistakeMapper;

    @Override
    public WeakPointsImprovementVO getImprovementReport(Long userId, String period) {
        String p = period == null || period.isBlank() ? "month" : period.toLowerCase();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart;
        LocalDateTime previousStart;
        if ("week".equals(p)) {
            currentStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay();
            previousStart = currentStart.minusWeeks(1);
        } else {
            currentStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
            previousStart = currentStart.minusMonths(1);
        }

        Map<String, long[]> current = aggregate(userId, currentStart, now);
        Map<String, long[]> previous = aggregate(userId, previousStart, currentStart);

        List<WeakPointsImprovementVO.KpChange> improved = new ArrayList<>();
        List<WeakPointsImprovementVO.KpChange> worsened = new ArrayList<>();
        List<String> allKps = new ArrayList<>(current.keySet());
        for (String kp : previous.keySet()) {
            if (!allKps.contains(kp)) allKps.add(kp);
        }

        double currentOverall = 0, previousOverall = 0;
        int cTotal = 0, pTotal = 0;
        for (String kp : allKps) {
            long[] c = current.getOrDefault(kp, new long[]{0, 0});
            long[] prv = previous.getOrDefault(kp, new long[]{0, 0});
            cTotal += c[0];
            pTotal += prv[0];
            currentOverall += c[1];
            previousOverall += prv[1];
            if (c[0] > 0 && prv[0] > 0) {
                double cRate = c[1] * 100.0 / c[0];
                double pRate = prv[1] * 100.0 / prv[0];
                double diff = cRate - pRate;
                WeakPointsImprovementVO.KpChange change = WeakPointsImprovementVO.KpChange.builder()
                        .knowledgePoint(kp).before(pRate).after(cRate).build();
                if (diff > 5) improved.add(change);
                else if (diff < -5) worsened.add(change);
            }
        }

        double overall = (pTotal == 0) ? (cTotal == 0 ? 0 : 100.0)
                : (cTotal == 0 ? -100.0 : (currentOverall / cTotal - previousOverall / pTotal) * 100.0);
        overall = Math.round(overall * 10.0) / 10.0;

        long unresolved = mistakeMapper.selectCount(new QueryWrapper<com.treepeople.leapmindtts.pojo.entity.PracticeMistake>()
                .eq("user_id", userId).eq("status", "UNRESOLVED"));
        long resolved = mistakeMapper.selectCount(new QueryWrapper<com.treepeople.leapmindtts.pojo.entity.PracticeMistake>()
                .eq("user_id", userId).eq("status", "RESOLVED"));

        String periodLabel = "week".equals(p)
                ? currentStart.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                : currentStart.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String suggestion;
        if (improved.size() > worsened.size()) {
            suggestion = "整体在进步，请保持当前练习节奏，继续巩固剩余薄弱点。";
        } else if (worsened.size() > improved.size()) {
            suggestion = "部分知识点出现退步，建议调整复习计划，优先攻克退步知识点。";
        } else {
            suggestion = "整体保持稳定，建议增加薄弱知识点针对性练习以进一步提升。";
        }

        return WeakPointsImprovementVO.builder()
                .period(periodLabel)
                .overallImprovement(overall)
                .improvedKps(improved)
                .worsenedKps(worsened)
                .totalWeakPoints((int) (unresolved + resolved))
                .highLevelCount((int) unresolved)
                .resolvedCount((int) resolved)
                .suggestion(suggestion)
                .build();
    }

    private Map<String, long[]> aggregate(Long userId, LocalDateTime from, LocalDateTime to) {
        Map<String, long[]> map = new LinkedHashMap<>();
        List<PracticeAnswerRecord> records = recordMapper.selectList(new QueryWrapper<PracticeAnswerRecord>()
                .eq("user_id", userId)
                .ge("answered_at", from)
                .lt("answered_at", to));
        for (PracticeAnswerRecord r : records) {
            if (r.getKnowledgePoint() == null) continue;
            long[] stat = map.computeIfAbsent(r.getKnowledgePoint(), k -> new long[]{0, 0});
            stat[0]++;
            if (Boolean.TRUE.equals(r.getCorrect())) stat[1]++;
        }
        return map;
    }
}
