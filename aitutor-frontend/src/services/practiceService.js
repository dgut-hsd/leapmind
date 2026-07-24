/**
 * M1 做题板块 —— API 服务
 *
 * ⚠️ 对接状态说明：
 *   ✅ = 已对接后端 API
 *   🔶 = 部分对接（数据转换中，部分字段暂缺）
 *   ❌ = 暂无后端接口，保留 Mock
 */

import { request } from './api';

// ============================================================================
// 常量映射表
// ============================================================================

const SUBJECT_MAP = { '数学': 'math', '英语': 'english', '计算机': 'computer', '语文': 'chinese', '物理': 'physics', '化学': 'chemistry', '生物': 'biology', '通用': 'general' };
const SUBJECT_REVERSE = Object.fromEntries(Object.entries(SUBJECT_MAP).map(([k, v]) => [v, k]));

const TYPE_BE_TO_FE = { 'SINGLE_CHOICE': 'single_choice', 'MULTIPLE_CHOICE': 'multi_choice', 'FILL_BLANK': 'fill_blank', 'SHORT_ANSWER': 'short_answer' };
const TYPE_FE_TO_BE = Object.fromEntries(Object.entries(TYPE_BE_TO_FE).map(([k, v]) => [v, k]));

const DIFF_BE_TO_FE = { 'BASIC': 1, 'ADVANCED': 3, 'HARD': 5 };
const DIFF_FE_TO_BE = { 1: 'BASIC', 2: 'BASIC', 3: 'ADVANCED', 4: 'ADVANCED', 5: 'HARD' };
const DIFF_LABEL = { 1: '★', 2: '★★', 3: '★★★', 4: '★★★★', 5: '★★★★★' };

const MISTAKE_STATUS_BE_TO_FE = { 'UNRESOLVED': 'unresolved', 'REVIEWING': 'reviewing', 'RESOLVED': 'resolved' };
const MISTAKE_STATUS_FE_TO_BE = Object.fromEntries(Object.entries(MISTAKE_STATUS_BE_TO_FE).map(([k, v]) => [v, k]));

// ============================================================================
// 工具函数
// ============================================================================

function unwrap(res) {
  if (res && typeof res === 'object' && 'data' in res) return res.data;
  return res;
}

function buildQuery(params) {
  const parts = [];
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
  }
  return parts.length ? '?' + parts.join('&') : '';
}

function frontendDifficulty(be) {
  const n = DIFF_BE_TO_FE[be] || 1;
  return { value: n, label: DIFF_LABEL[n] || '★' };
}

// ============================================================================
// Transform 函数 —— 将后端 JSON 转为前端期望的数据结构
// ============================================================================

/**
 * GET /filters → 前端 filterOptions
 */
function transformFilterOptions(data) {
  const subjects = (data.subjects || []).map(s => ({ value: SUBJECT_MAP[s] || 'general', label: s }));
  const grades = (data.gradeLevels || []).map(g => ({ value: g, label: g }));
  // 后端返回扁平章节列表，前端期望按科目分组；此处铺到所有科目 + general 兜底
  const chapterItems = (data.chapters || []).map(ch => ({ value: ch, label: ch }));
  const chapters = { general: chapterItems };
  subjects.forEach(s => { chapters[s.value] = chapterItems; });
  const typeLabels = { single_choice: '单选题', multi_choice: '多选题', fill_blank: '填空题', short_answer: '简答题' };
  const types = (data.questionTypes || []).map(t => {
    const fe = TYPE_BE_TO_FE[t];
    return { value: fe || t.toLowerCase(), label: (fe && typeLabels[fe]) || t };
  });
  const difficulties = (data.difficulties || []).map(d => frontendDifficulty(d));
  return { subjects, grades, chapters, types, difficulties };
}

/**
 * 单题转换
 */
function transformQuestion(q, includeAnswer = true) {
  if (!q) return null;
  const options = q.options || {};
  const optionList = Object.entries(options).map(([k, v]) => k + '. ' + v);
  const fe = {
    questionId: q.id,
    type: TYPE_BE_TO_FE[q.questionType] || 'single_choice',
    difficulty: DIFF_BE_TO_FE[q.difficulty] || 1,
    subject: SUBJECT_MAP[q.subject] || q.subject,
    grade: q.gradeLevel || '',
    chapter: q.chapter || '',
    content: {
      stem: q.content || q.title || '',
      options: optionList,
      images: [],
    },
    knowledgePoints: q.knowledgePoint ? [{ id: 0, name: q.knowledgePoint }] : [],
    answerSteps: [],
    explanation: q.analysis || '',
  };
  if (includeAnswer) {
    fe.correctAnswer = q.correctAnswer || '';
  }
  return fe;
}

/**
 * 题目列表分页转换
 */
function transformQuestionList(data) {
  return {
    total: data.total || 0,
    page: data.page || 1,
    size: data.pageSize || 12,
    items: (data.records || []).map(q => transformQuestion(q, false)),
  };
}

/**
 * POST /submit → 前端 submitAnswer 结果
 */
function transformSubmitResult(data) {
  const q = data.question || {};
  const record = data.record || {};
  const dashboard = data.dashboard || {};
  return {
    recordId: record.id,
    isCorrect: Boolean(data.correct),
    correctAnswer: { selected: q.correctAnswer || '' },
    explanation: q.analysis || '',
    knowledgePoints: q.knowledgePoint ? [{ id: 0, name: q.knowledgePoint }] : [],
    answerSteps: [],
    pointsEarned: data.points || 0,
    totalPoints: dashboard.totalPoints || 0,
    judgeScore: data.judgeScore,
    judgeFeedback: data.judgeFeedback,
  };
}

/**
 * GET /mistakes 单条错题转换
 */
function transformMistake(m) {
  return {
    id: m.id,
    questionId: m.questionId,
    questionContent: {
      stem: m.content || m.questionTitle || '',
      options: [],
      images: [],
    },
    correctAnswer: '',               // 🔶 后端 toMistakeMap 不含 correctAnswer
    userAnswer: { selected: '' },    // 🔶 同上，需额外查询答题记录
    wrongReasonTag: '',              // 🔶 后端暂无错因标签
    knowledgePoints: m.knowledgePoint ? [{ id: 0, name: m.knowledgePoint }] : [],
    status: MISTAKE_STATUS_BE_TO_FE[m.status] || 'unresolved',
    isKeyFocus: Boolean(m.doubtful),
    subject: SUBJECT_MAP[m.track] || 'general',
    createdAt: m.lastWrongAt || '',
    reviewCount: m.reviewCount || 0,
    lastReviewAt: m.lastReviewAt || null,
    chapter: m.chapter || '',
    difficulty: DIFF_BE_TO_FE[m.difficulty] || 1,
  };
}

/**
 * GET /statistics → 前端 statistics
 */
function transformStatistics(data) {
  return {
    summary: {
      totalQuestions: data.totalAnswers || 0,
      correctRate: (data.accuracy || 0) / 100,
      avgTimeSpent: data.averageDurationSeconds || 0,
      streakDays: 0,  // 🔶 statistics 接口不含连签，需再调 dashboard
    },
    dailyTrend: (data.trend || []).map(t => ({
      date: (t.date || '').slice(5),
      count: t.count || 0,
      correctRate: (t.accuracy || 0) / 100,
    })),
    byKnowledgePoint: (data.knowledgeDistribution || []).map(k => ({
      kpId: 0,
      kpName: k.name || k.knowledgePoint || '',
      total: k.count || 0,
      correct: k.correct || 0,
      rate: (k.accuracy || 0) / 100,
    })),
    wrongReasons: [],  // 🔶 后端暂无错因归类
  };
}

/**
 * GET /leaderboards → 前端 ranking 列表
 */
function transformRanking(data) {
  return (data.trackRanking || []).map(r => ({
    rank: r.rank,
    userId: r.userId,
    nickname: r.nickname || '',
    avatar: r.avatar || '',
    points: r.points || 0,
    streakDays: r.streakDays || 0,
  }));
}

// ============================================================================
// ✅ 已对接后端 API
// ============================================================================

/**
 * 获取筛选选项  ✅ GET /api/practice/filters
 */
export async function getFilterOptions() {
  const res = await request('/api/practice/filters');
  return transformFilterOptions(unwrap(res));
}

/**
 * 查询题目列表  ✅ GET /api/practice/questions
 */
export async function getQuestions(params = {}) {
  const beParams = {};
  if (params.subject) beParams.subject = SUBJECT_REVERSE[params.subject] || params.subject;
  if (params.grade) beParams.gradeLevel = params.grade;
  if (params.chapter) beParams.chapter = params.chapter;
  if (params.type) beParams.questionType = TYPE_FE_TO_BE[params.type] || params.type;
  if (params.difficulty) beParams.difficulty = DIFF_FE_TO_BE[params.difficulty] || params.difficulty;
  if (params.keyword) beParams.keyword = params.keyword;
  beParams.page = params.page || 1;
  beParams.pageSize = params.size || 12;

  const res = await request('/api/practice/questions' + buildQuery(beParams));
  return transformQuestionList(unwrap(res));
}

/**
 * 获取单题详情  ✅ GET /api/practice/questions/{id}
 */
export async function getQuestionDetail(questionId) {
  const res = await request('/api/practice/questions/' + questionId);
  return transformQuestion(unwrap(res), true);
}

/**
 * 提交答案  ✅ POST /api/practice/submit
 * 💡 会自动检测：真实 DB ID → 后端判题；Mock ID（有 _originalId）→ 本地判题
 */
export async function submitAnswer(params = {}) {
  let userAnswer = '';
  if (typeof params.answer?.selected === 'string') {
    userAnswer = params.answer.selected;
  } else if (Array.isArray(params.answer?.selected)) {
    userAnswer = params.answer.selected.join(',');
  } else if (params.answer?.text) {
    userAnswer = params.answer.text;
  }
  const body = {
    questionId: params.questionId,
    userAnswer,
    durationSeconds: params.timeSpent || 0,
    mode: 'SEQUENTIAL',
  };

  try {
    const res = await request('/api/practice/submit', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return transformSubmitResult(unwrap(res));
  } catch (err) {
    // Mock 会话回退：generateSession 使用 Mock 时 questionId 为假 ID，后端会返回 400/404
    if (params._originalId) {
      console.warn('[M1] submitAnswer 后端不可用，使用本地 Mock 判题');
      return _mockJudge(params);
    }
    throw err;
  }
}

/**
 * 获取排行榜  ✅ GET /api/practice/leaderboards
 */
export async function getRanking(type = 'daily', limit = 20) {
  const res = await request('/api/practice/leaderboards');
  const list = transformRanking(unwrap(res));
  return list.slice(0, limit);
}

/**
 * 每日签到  ✅ POST /api/practice/checkin
 */
export async function dailyCheckin() {
  const res = await request('/api/practice/checkin', { method: 'POST', body: '{}' });
  const data = unwrap(res);
  return {
    pointsEarned: data.points || 0,
    totalPoints: 0,
    streakDays: 0,
    alreadyChecked: data.alreadyChecked || false,
  };
}

// ============================================================================
// 🔶 部分对接（数据字段不全，后续需要完善）
// ============================================================================

/**
 * 获取错题本  🔶 GET /api/practice/mistakes
 * ⚠️ 后端返回不含 correctAnswer / userAnswer / wrongReasonTag，部分字段为兜底值
 */
export async function getWrongQuestions(params = {}) {
  const beParams = {};
  if (params.status) beParams.status = MISTAKE_STATUS_FE_TO_BE[params.status] || params.status;
  if (params.chapter) beParams.chapter = params.chapter;
  if (params.kpName) beParams.knowledgePoint = params.kpName;

  const res = await request('/api/practice/mistakes' + buildQuery(beParams));
  let list = (unwrap(res) || []).map(transformMistake);

  // 前端额外筛选（keyword / subject / timeRange 后端暂不支持）
  if (params.keyword) list = list.filter(q => q.questionContent?.stem?.includes(params.keyword));
  if (params.subject) list = list.filter(q => q.subject === params.subject);
  if (params.timeRange === 'week') list = list.filter(q => new Date(q.createdAt) > new Date(Date.now() - 7 * 86400000));
  if (params.timeRange === 'month') list = list.filter(q => new Date(q.createdAt) > new Date(Date.now() - 30 * 86400000));

  const page = params.page || 1;
  const size = params.size || 20;
  const start = (page - 1) * size;
  return { total: list.length, page, size, items: list.slice(start, start + size) };
}

/**
 * 标记/取消重点  🔶 PATCH /api/practice/mistake-book/{id}
 * ⚠️ 需要先获取当前状态再取反，当前为桩实现
 */
export async function toggleFocus(wrongQuestionId) {
  // TODO: 先 GET /mistakes 获取当前 doubtful 值，再 PATCH 取反
  console.warn('[M1] toggleFocus 暂未对接，需获取当前错题状态后再 PATCH');
  return { success: false, reason: 'TODO: 对接 PATCH /api/practice/mistake-book/{id}' };
}

/**
 * 获取练习统计  🔶 GET /api/practice/statistics
 * ⚠️ wrongReasons 字段暂空，streakDays 需再调 dashboard
 */
export async function getStatistics(params = {}) {
  const rangeMap = { week: 'week', month: 'month', all: 'all' };
  const range = rangeMap[params.period || params.range] || 'week';
  const res = await request('/api/practice/statistics?range=' + range);
  return transformStatistics(unwrap(res));
}

// ============================================================================
// ❌ 暂无后端对应接口 / 🔶 待后续完善
// ============================================================================

// 保留 Mock 数据引用（仅 fallback 阶段使用）
let _mockQuestions = null;
let _mockPoints = null;
async function _loadMock() {
  if (!_mockQuestions) {
    const mod = await import('../data/mockPractice');
    _mockQuestions = mod.mockQuestions;
    _mockPoints = mod.mockPoints;
  }
}

/**
 * Mock 本地判题（当后端不可用时的降级方案）
 * 🔶 预期后续全部走后端，此函数仅作过渡
 */
async function _mockJudge(params) {
  await _loadMock();
  const { questionId, answer, timeSpent, _originalId } = params;
  const matchId = _originalId || questionId;
  const question = _mockQuestions.find((q) => q.questionId === matchId);
  if (!question) {
    return { recordId: Date.now(), isCorrect: false, error: '题目不存在' };
  }
  let isCorrect = false;
  if (question.type === 'single_choice') {
    isCorrect = answer?.selected === question.correctAnswer;
  } else if (question.type === 'multi_choice') {
    const selected = (answer?.selected || []).sort().join(',');
    const expected = question.correctAnswer.split(',').sort().join(',');
    isCorrect = selected === expected;
  } else {
    isCorrect = (answer?.text || '').trim().toLowerCase() === question.correctAnswer.trim().toLowerCase();
  }
  return {
    recordId: Date.now(),
    isCorrect,
    correctAnswer:
      question.type === 'single_choice' ? { selected: question.correctAnswer }
      : question.type === 'multi_choice' ? { selected: question.correctAnswer.split(',') }
      : { text: question.correctAnswer },
    explanation: question.explanation,
    knowledgePoints: question.knowledgePoints,
    answerSteps: question.answerSteps,
    pointsEarned: isCorrect ? 10 : 0,
    totalPoints: (_mockPoints?.totalPoints || 0) + (isCorrect ? 10 : 0),
  };
}

/**
 * 逐题获取下一题  ✅ GET /api/practice/next
 * 💡 后端支持 6 种模式：SEQUENTIAL / RANDOM / MISTAKES / FREE_PRACTICE / AFTER_CLASS / MISTAKE_REDO
 * 🔶 当前仅 generateSession 内部使用，后续可暴露给 PracticePage 做"流式按题拉取"交互
 */
export async function getNextQuestion(params = {}) {
  const beParams = { mode: params.mode || 'SEQUENTIAL' };
  if (params.subject) beParams.subject = SUBJECT_REVERSE[params.subject] || params.subject;
  if (params.grade) beParams.gradeLevel = params.grade;
  if (params.chapter) beParams.chapter = params.chapter;
  if (params.knowledgePoint) beParams.knowledgePoint = params.knowledgePoint;
  if (params.questionType) beParams.questionType = TYPE_FE_TO_BE[params.questionType] || params.questionType;
  if (params.difficulty) beParams.difficulty = DIFF_FE_TO_BE[params.difficulty] || params.difficulty;
  if (params.lessonId) beParams.lessonId = params.lessonId;

  const res = await request('/api/practice/next' + buildQuery(beParams));
  const data = unwrap(res);
  // GET /next 返回 {question, mode, stats}，question 不含 correctAnswer
  return {
    question: transformQuestion(data.question, false),
    mode: data.mode,
    stats: data.stats, // dashboard 数据（含 totalPoints 等）
  };
}

/**
 * 生成练习会话  ✅ GET /api/practice/questions（批量拉取，含答案）
 * 💡 后端原始设计为逐题 GET /next，此处用 GET /questions 批量获取以适配前端现有交互
 * 💡 后端不可用时自动回退到 Mock 数据
 */
export async function generateSession(params = {}) {
  const count = params.questionCount || 10;
  const beParams = { page: 1, pageSize: count };
  if (params.subject) beParams.subject = SUBJECT_REVERSE[params.subject] || params.subject;
  if (params.grade) beParams.gradeLevel = params.grade;
  if (params.chapter) beParams.chapter = params.chapter;
  if (params.questionType) beParams.questionType = TYPE_FE_TO_BE[params.questionType] || params.questionType;
  if (params.difficulty) beParams.difficulty = DIFF_FE_TO_BE[params.difficulty] || params.difficulty;

  try {
    const res = await request('/api/practice/questions' + buildQuery(beParams));
    const data = unwrap(res);
    const items = (data.records || []).map(q => transformQuestion(q, true));
    if (items.length === 0) throw new Error('题库无匹配题目');

    // 循环填充到指定数量（后端可能返回不足 count 条）
    const questions = [];
    for (let i = 0; i < count; i++) {
      questions.push({ ...items[i % items.length] });
    }
    return {
      sessionId: 'sess_' + Date.now(),
      questions: questions.slice(0, count),
      totalCount: count,
    };
  } catch (err) {
    // 🔶 后端不可用，回退到 Mock 数据
    console.warn('[M1] 后端不可用，generateSession 回退到 Mock:', err.message);
    await _loadMock();
    let pool = [..._mockQuestions];
    if (params.subject) pool = pool.filter(q => q.subject === params.subject);
    if (params.knowledgePointIds?.length) {
      pool = pool.filter(q => q.knowledgePoints.some(kp => params.knowledgePointIds.includes(kp.id)));
    }
    const questions = [];
    for (let i = 0; i < count; i++) {
      const src = pool[i % pool.length];
      // _originalId 标记为 Mock 来源，submitAnswer 会据此走本地判题
      questions.push({ ...src, questionId: 100 + i, _originalId: src.questionId });
    }
    return { sessionId: 'sess_' + Date.now(), questions, totalCount: questions.length };
  }
}

/**
 * 删除错题  ❌ 后端无此接口
 * 💡 预期：后端增加 DELETE /api/practice/mistakes/{id} 或软删除标记
 */
export async function deleteWrongQuestion(wrongQuestionId) {
  console.warn('[M1] deleteWrongQuestion 后端无对应接口，仅前端模拟');
  return { success: true };
}

/**
 * 获取积分明细  ❌ 后端无独立接口（数据散落在 dashboard）
 * 💡 预期：后端增加 GET /api/practice/points?range=week 独立积分接口
 */
export async function getPointsHistory(params = {}) {
  await _loadMock();
  return _mockPoints;
}

