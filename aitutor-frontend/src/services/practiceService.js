/**
 * M1 做题板块 —— API 服务
 * 当前使用 Mock 数据，后续对接真实接口时替换 fetch 调用
 */

// ----- TODO: 对接真实接口时，取消以下注释并删除 Mock 逻辑 -----
// import { request, ApiError } from './api';

import {
  mockQuestions,
  mockFilterOptions,
  mockWrongQuestions,
  mockStatistics,
  mockRanking,
  mockPoints,
} from "../data/mockPractice";

// 模拟网络延迟
const delay = (ms = 300) => new Promise((r) => setTimeout(r, ms));

// ============== 题库 ==============

/**
 * 查询题目列表（分页+筛选）
 */
export async function getQuestions(params = {}) {
  await delay();
  let list = [...mockQuestions];
  if (params.subject) list = list.filter((q) => q.subject === params.subject);
  if (params.grade) list = list.filter((q) => q.grade === params.grade);
  if (params.chapter) list = list.filter((q) => q.chapter === params.chapter);
  if (params.type) list = list.filter((q) => q.type === params.type);
  if (params.difficulty)
    list = list.filter((q) => q.difficulty === Number(params.difficulty));
  if (params.keyword)
    list = list.filter((q) => q.content?.stem?.includes(params.keyword) || q.chapter?.includes(params.keyword));
  const page = params.page || 1;
  const size = params.size || 20;
  const start = (page - 1) * size;
  return {
    total: list.length,
    page,
    size,
    items: list.slice(start, start + size),
  };
}

/**
 * 获取筛选选项
 */
export async function getFilterOptions() {
  await delay(100);
  return mockFilterOptions;
}

/**
 * 获取单题详情
 */
export async function getQuestionDetail(questionId) {
  await delay(200);
  return mockQuestions.find((q) => q.questionId === questionId) || null;
}

// ============== 练习 ==============

/**
 * 生成练习会话
 */
export async function generateSession(params = {}) {
  await delay(400);
  const count = params.questionCount || 10;
  // 简单模拟：从题库中选取题目
  let pool = [...mockQuestions];
  if (params.subject) pool = pool.filter((q) => q.subject === params.subject);
  if (params.knowledgePointIds?.length) {
    pool = pool.filter((q) =>
      q.knowledgePoints.some((kp) => params.knowledgePointIds.includes(kp.id))
    );
  }
  // 循环填充到指定数量
  const questions = [];
  for (let i = 0; i < count; i++) {
    const src = pool[i % pool.length];
    questions.push({ ...src, questionId: 100 + i, _originalId: src.questionId });
  }
  return {
    sessionId: "sess_" + Date.now(),
    questions,
    totalCount: questions.length,
  };
}

/**
 * 提交答案
 */
export async function submitAnswer(params = {}) {
  await delay(500);
  const { questionId, answer, timeSpent, _originalId } = params;
  // 优先用 _originalId（generateSession 生成时带上的原始题目 ID），
  // 否则直接匹配 questionId（用户从题库页直接进入的场景）
  const matchId = _originalId || questionId;
  const question = mockQuestions.find((q) => q.questionId === matchId);
  if (!question) {
    return { recordId: Date.now(), isCorrect: false, error: "题目不存在" };
  }
  let isCorrect = false;
  if (question.type === "single_choice") {
    isCorrect = answer?.selected === question.correctAnswer;
  } else if (question.type === "multi_choice") {
    // 多选题：对比排序后的选中项与正确答案
    const selected = (answer?.selected || []).sort().join(",");
    const expected = question.correctAnswer.split(",").sort().join(",");
    isCorrect = selected === expected;
  } else if (question.type === "fill_blank" || question.type === "short_answer") {
    isCorrect =
      answer?.text?.trim().toLowerCase() ===
      question.correctAnswer.trim().toLowerCase();
  }
  return {
    recordId: Date.now(),
    isCorrect,
    correctAnswer:
      question.type === "single_choice"
        ? { selected: question.correctAnswer }
        : question.type === "multi_choice"
          ? { selected: question.correctAnswer.split(",") }
          : { text: question.correctAnswer },
    explanation: question.explanation,
    knowledgePoints: question.knowledgePoints,
    answerSteps: question.answerSteps,
    pointsEarned: isCorrect ? 10 : 0,
    totalPoints: mockPoints.totalPoints + (isCorrect ? 10 : 0),
  };
}

// ============== 错题本 ==============

/**
 * 获取错题本列表
 */
export async function getWrongQuestions(params = {}) {
  await delay(300);
  let list = [...mockWrongQuestions];
  if (params.status) list = list.filter((q) => q.status === params.status);
  if (params.keyword) list = list.filter((q) => q.questionContent?.stem?.includes(params.keyword));
  if (params.subject) list = list.filter((q) => q.subject === params.subject || mockQuestions.find(mq => mq.questionId === q.questionId)?.subject === params.subject);
  if (params.kpName) list = list.filter((q) => q.knowledgePoints?.some((kp) => kp.name === params.kpName));
  if (params.timeRange === "week") list = list.filter((q) => new Date(q.createdAt) > new Date(Date.now() - 7 * 86400000));
  if (params.timeRange === "month") list = list.filter((q) => new Date(q.createdAt) > new Date(Date.now() - 30 * 86400000));
  const page = params.page || 1;
  const size = params.size || 20;
  const start = (page - 1) * size;
  return { total: list.length, page, size, items: list.slice(start, start + size) };
}

/**
 * 标记/取消重点
 */
export async function toggleFocus(wrongQuestionId) {
  await delay(200);
  return { success: true };
}

/**
 * 删除错题
 */
export async function deleteWrongQuestion(wrongQuestionId) {
  await delay(200);
  return { success: true };
}

// ============== 统计 ==============

/**
 * 获取练习统计
 */
export async function getStatistics(params = {}) {
  await delay(400);
  return mockStatistics;
}

// ============== 排行榜 ==============

/**
 * 获取排行榜
 */
export async function getRanking(type = "daily", limit = 20) {
  await delay(300);
  return mockRanking.daily.slice(0, limit);
}

// ============== 积分 ==============

/**
 * 获取积分明细
 */
export async function getPointsHistory(params = {}) {
  await delay(300);
  return mockPoints;
}

/**
 * 每日签到
 */
export async function dailyCheckin() {
  await delay(300);
  return { pointsEarned: 5, totalPoints: mockPoints.totalPoints + 5, streakDays: mockPoints.streakDays + 1 };
}
