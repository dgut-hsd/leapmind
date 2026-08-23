/**
 * M6 学习画像服务。
 *
 * 页面只依赖本文件返回的规范结构，不直接依赖后端当前的字段命名。
 * 当计划接口尚未上线或返回不完整时，会返回可展示的大学生演示数据，
 * 并通过顶层 `isDemo: true` 明确标记。
 */

import { get, post } from './api';

const PROFILE_ENDPOINT = (userId) => `/api/user-profile/${encodeURIComponent(userId)}`;
const KNOWLEDGE_STATUS_ENDPOINT = (userId) => `${PROFILE_ENDPOINT(userId)}/knowledge-status`;
const REVIEW_REMINDERS_ENDPOINT = (userId) => `${PROFILE_ENDPOINT(userId)}/review-reminders`;
const MARK_REVIEWED_ENDPOINT = (userId) => `${PROFILE_ENDPOINT(userId)}/mark-reviewed`;
const LEARNING_EVENTS_ENDPOINT = (userId) => `${PROFILE_ENDPOINT(userId)}/record-event`;

export const LEARNING_EVENT_TYPES = Object.freeze({
  ANSWER_QUESTION: 'answer_question',
  FINISH_PRACTICE: 'finish_practice',
  REQUEST_EXPLANATION: 'request_explanation',
  EXPLANATION_FEEDBACK: 'explanation_feedback',
  WEAK_POINT_CHANGED: 'weak_point_changed',
  LECTURE_INTERACT: 'lecture_interact',
  LESSON_MATERIAL_USED: 'lesson_material_used',
  ASK_DOUBT: 'ask_doubt',
  MARK_REVIEWED: 'mark_reviewed',
  PREFERENCE_CHANGED: 'preference_changed',
});

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function pick(source, keys, fallback) {
  if (!isObject(source)) return fallback;

  for (const key of keys) {
    const value = source[key];
    if (value !== undefined && value !== null && value !== '') return value;
  }
  return fallback;
}

function numberValue(value, fallback = 0) {
  if (typeof value === 'string') {
    value = value.replace('%', '').trim();
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function percentage(value, fallback = 0) {
  return Math.min(100, Math.max(0, numberValue(value, fallback)));
}

function masteryPercentage(value, fallback = 0) {
  const normalized = numberValue(value, fallback);
  return percentage(normalized >= 0 && normalized <= 1 ? normalized * 100 : normalized, fallback);
}

/**
 * 兼容 fetch 风格响应、Axios 风格响应，以及 ApiResponse 的多层 data 包装。
 */
function unwrapResponse(response) {
  let current = response;

  for (let depth = 0; depth < 4; depth += 1) {
    if (!isObject(current) || !hasOwn(current, 'data')) break;

    const keys = Object.keys(current);
    const looksLikeWrapper = keys.length === 1 || keys.some((key) => (
      ['code', 'status', 'success', 'message', 'msg', 'timestamp'].includes(key)
    ));

    if (!looksLikeWrapper) break;
    current = current.data;
  }

  return current;
}

function collectionFrom(source, keys) {
  if (Array.isArray(source)) return source;
  if (!isObject(source)) return [];

  for (const key of keys) {
    if (Array.isArray(source[key])) return source[key];
    if (isObject(source[key])) {
      const nested = collectionFrom(source[key], ['list', 'records', 'content', 'items', 'children']);
      if (nested.length) return nested;
    }
  }

  return [];
}

function statusFrom(value, mastery) {
  const status = String(value || '').toLowerCase();
  const aliases = {
    mastered: 'mastered',
    proficient: 'mastered',
    completed: 'mastered',
    '\u5df2\u638c\u63e1': 'mastered',
    learning: 'learning',
    studying: 'learning',
    in_progress: 'learning',
    '\u5b66\u4e60\u4e2d': 'learning',
    review: 'review',
    reviewing: 'review',
    due: 'review',
    '\u5f85\u590d\u4e60': 'review',
    weak: 'weak',
    unmastered: 'weak',
    risky: 'weak',
    '\u8584\u5f31': 'weak',
    // M6 backend masteryStatus values
    basic_mastery: 'review',
    consolidating: 'learning',
    insufficient_evidence: 'weak',
    available: 'learning',
    empty: 'weak',
    not_ready: 'weak',
  };

  if (aliases[status]) return aliases[status];
  if (mastery >= 85) return 'mastered';
  if (mastery >= 60) return 'learning';
  return 'weak';
}

function dateOffset(days) {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

function createDemoKnowledgeTree() {
  return [
    {
      id: 'subject-calculus',
      name: '\u9ad8\u7b49\u6570\u5b66',
      subject: '\u9ad8\u7b49\u6570\u5b66',
      mastery: 82,
      status: 'learning',
      trend: 5,
      type: 'subject',
      children: [
        { id: 'calculus-limit', name: '\u6781\u9650\u4e0e\u8fde\u7eed', subject: '\u9ad8\u7b49\u6570\u5b66', mastery: 91, status: 'mastered', trend: 3, type: 'knowledgePoint', children: [] },
        { id: 'calculus-derivative', name: '\u5bfc\u6570\u4e0e\u5fae\u5206', subject: '\u9ad8\u7b49\u6570\u5b66', mastery: 78, status: 'review', trend: 6, type: 'knowledgePoint', children: [] },
        { id: 'calculus-integral', name: '\u4e0d\u5b9a\u79ef\u5206', subject: '\u9ad8\u7b49\u6570\u5b66', mastery: 68, status: 'learning', trend: 4, type: 'knowledgePoint', children: [] },
      ],
    },
    {
      id: 'subject-data-structure',
      name: '\u6570\u636e\u7ed3\u6784',
      subject: '\u6570\u636e\u7ed3\u6784',
      mastery: 76,
      status: 'learning',
      trend: 7,
      type: 'subject',
      children: [
        { id: 'ds-linear-list', name: '\u7ebf\u6027\u8868', subject: '\u6570\u636e\u7ed3\u6784', mastery: 88, status: 'mastered', trend: 2, type: 'knowledgePoint', children: [] },
        { id: 'ds-binary-tree', name: '\u4e8c\u53c9\u6811', subject: '\u6570\u636e\u7ed3\u6784', mastery: 74, status: 'learning', trend: 8, type: 'knowledgePoint', children: [] },
        { id: 'ds-graph', name: '\u56fe\u7684\u904d\u5386', subject: '\u6570\u636e\u7ed3\u6784', mastery: 57, status: 'weak', trend: -2, type: 'knowledgePoint', children: [] },
      ],
    },
    {
      id: 'subject-computer-network',
      name: '\u8ba1\u7b97\u673a\u7f51\u7edc',
      subject: '\u8ba1\u7b97\u673a\u7f51\u7edc',
      mastery: 71,
      status: 'learning',
      trend: 3,
      type: 'subject',
      children: [
        { id: 'network-tcp', name: 'TCP \u53ef\u9760\u4f20\u8f93', subject: '\u8ba1\u7b97\u673a\u7f51\u7edc', mastery: 79, status: 'learning', trend: 5, type: 'knowledgePoint', children: [] },
        { id: 'network-http', name: 'HTTP \u534f\u8bae', subject: '\u8ba1\u7b97\u673a\u7f51\u7edc', mastery: 84, status: 'learning', trend: 4, type: 'knowledgePoint', children: [] },
        { id: 'network-subnet', name: '\u5b50\u7f51\u5212\u5206', subject: '\u8ba1\u7b97\u673a\u7f51\u7edc', mastery: 51, status: 'weak', trend: -3, type: 'knowledgePoint', children: [] },
      ],
    },
  ];
}

function createDemoProfile(userId) {
  const knowledgeTree = createDemoKnowledgeTree();

  return {
    isDemo: true,
    demoReason: '\u5b66\u4e60\u753b\u50cf\u63a5\u53e3\u5c1a\u672a\u8fd4\u56de\u5b8c\u6574\u6570\u636e\uff0c\u5f53\u524d\u5c55\u793a\u5927\u5b66\u751f\u6f14\u793a\u6570\u636e\u3002',
    user: {
      id: String(userId || 'demo-user'),
      name: '\u5927\u5b66\u751f\u7528\u6237',
      avatar: '',
      university: 'LeapMind \u793a\u8303\u5927\u5b66',
      major: '\u8ba1\u7b97\u673a\u79d1\u5b66\u4e0e\u6280\u672f',
      grade: '\u5927\u4e09',
      bio: '\u6b63\u5728\u51c6\u5907\u4e13\u4e1a\u8bfe\u671f\u672b\u8003\u8bd5',
    },
    summary: {
      overallMastery: 78,
      masteryChange: 6,
      studyDays: 46,
      streakDays: 7,
      weeklyStudyMinutes: 385,
      weeklyGoalMinutes: 480,
      weeklyGoalProgress: 80,
      completedCourses: 12,
      completedExercises: 268,
      message: '\u672c\u5468\u5b66\u4e60\u8282\u594f\u7a33\u5b9a\uff0c\u5bfc\u6570\u548c\u4e8c\u53c9\u6811\u63d0\u5347\u660e\u663e\uff0c\u5efa\u8bae\u4f18\u5148\u590d\u4e60\u56fe\u7684\u904d\u5386\u4e0e\u5b50\u7f51\u5212\u5206\u3002',
    },
    stats: [
      { key: 'studyTime', label: '\u672c\u5468\u5b66\u4e60', value: 385, unit: '\u5206\u949f', change: 12 },
      { key: 'mastery', label: '\u7efc\u5408\u638c\u63e1\u5ea6', value: 78, unit: '%', change: 6 },
      { key: 'streak', label: '\u8fde\u7eed\u5b66\u4e60', value: 7, unit: '\u5929', change: 2 },
      { key: 'exercises', label: '\u7d2f\u8ba1\u7ec3\u4e60', value: 268, unit: '\u9898', change: 34 },
    ],
    dimensions: [
      { key: 'concept', label: '\u6982\u5ff5\u7406\u89e3', value: 86, max: 100 },
      { key: 'application', label: '\u77e5\u8bc6\u5e94\u7528', value: 74, max: 100 },
      { key: 'calculation', label: '\u8ba1\u7b97\u51c6\u786e', value: 81, max: 100 },
      { key: 'reasoning', label: '\u903b\u8f91\u63a8\u7406', value: 77, max: 100 },
      { key: 'retention', label: '\u8bb0\u5fc6\u5de9\u56fa', value: 69, max: 100 },
      { key: 'independence', label: '\u81ea\u4e3b\u5b66\u4e60', value: 83, max: 100 },
    ],
    knowledgeTree,
    timeline: [
      { id: 'activity-1', type: 'course', title: '\u5b8c\u6210\u300a\u4e8c\u53c9\u6811\u7684\u904d\u5386\u300b', description: '\u89c2\u770b\u8bb2\u89e3\u5e76\u5b8c\u6210 12 \u9053\u7ec3\u4e60', subject: '\u6570\u636e\u7ed3\u6784', score: 88, time: dateOffset(0) },
      { id: 'activity-2', type: 'review', title: '\u5b8c\u6210\u5bfc\u6570\u9519\u9898\u590d\u4e60', description: '\u638c\u63e1\u5ea6\u4ece 72% \u63d0\u5347\u81f3 78%', subject: '\u9ad8\u7b49\u6570\u5b66', score: 84, time: dateOffset(-1) },
      { id: 'activity-3', type: 'quiz', title: '\u5b8c\u6210 TCP \u534f\u8bae\u968f\u5802\u6d4b\u9a8c', description: '\u6b63\u786e 9 \u9898\uff0c\u9519\u8bef 1 \u9898', subject: '\u8ba1\u7b97\u673a\u7f51\u7edc', score: 90, time: dateOffset(-3) },
      { id: 'activity-4', type: 'milestone', title: '\u8fde\u7eed\u5b66\u4e60\u8fbe\u5230 7 \u5929', description: '\u672c\u5468\u7d2f\u8ba1\u5b66\u4e60 6 \u5c0f\u65f6 25 \u5206\u949f', subject: '\u5b66\u4e60\u91cc\u7a0b\u7891', score: null, time: dateOffset(-4) },
    ],
    reminders: [
      { id: 'reminder-1', knowledgePointId: 'ds-graph', title: '\u590d\u4e60\u56fe\u7684 DFS \u4e0e BFS', subject: '\u6570\u636e\u7ed3\u6784', dueAt: dateOffset(1), priority: 'high', reason: '\u8fd1\u4e24\u6b21\u7ec3\u4e60\u6b63\u786e\u7387\u4f4e\u4e8e 60%', status: 'pending' },
      { id: 'reminder-2', knowledgePointId: 'network-subnet', title: '\u5de9\u56fa\u5b50\u7f51\u5212\u5206', subject: '\u8ba1\u7b97\u673a\u7f51\u7edc', dueAt: dateOffset(2), priority: 'high', reason: '\u638c\u63e1\u5ea6\u8f83\u4f4e\uff0c\u5efa\u8bae\u518d\u5b8c\u6210\u4e00\u7ec4\u8ba1\u7b97\u9898', status: 'pending' },
      { id: 'reminder-3', knowledgePointId: 'calculus-derivative', title: '\u5bfc\u6570\u9636\u6bb5\u6027\u590d\u4e60', subject: '\u9ad8\u7b49\u6570\u5b66', dueAt: dateOffset(3), priority: 'medium', reason: '\u6309\u9057\u5fd8\u66f2\u7ebf\u8fdb\u884c\u5de9\u56fa', status: 'pending' },
    ],
    preferences: {
      weeklyGoalMinutes: 480,
      preferredStudyTime: '\u665a\u4e0a 20:00\u201322:00',
      preferredSessionMinutes: 45,
      reminderEnabled: true,
      difficulty: 'adaptive',
      learningStyle: '\u89c6\u9891\u8bb2\u89e3 + \u9636\u6bb5\u7ec3\u4e60',
    },
  };
}

function findDemoKnowledgePoint(knowledgePointId) {
  const points = createDemoKnowledgeTree().flatMap((subject) => subject.children);
  return points.find((point) => String(point.id) === String(knowledgePointId)) || points[1];
}

function createDemoDetail(knowledgePointId) {
  const point = findDemoKnowledgePoint(knowledgePointId);
  const isGraph = point.id === 'ds-graph';
  const isSubnet = point.id === 'network-subnet';
  const mastery = point.mastery;

  return {
    isDemo: true,
    demoReason: '\u77e5\u8bc6\u70b9\u8be6\u60c5\u63a5\u53e3\u5c1a\u672a\u8fd4\u56de\u5b8c\u6574\u6570\u636e\uff0c\u5f53\u524d\u5c55\u793a\u5927\u5b66\u751f\u6f14\u793a\u6570\u636e\u3002',
    id: String(knowledgePointId || point.id),
    name: point.name,
    subject: point.subject,
    mastery,
    status: point.status,
    trend: {
      direction: point.trend > 0 ? 'up' : point.trend < 0 ? 'down' : 'stable',
      value: Math.abs(point.trend),
      label: point.trend > 0 ? `\u8f83\u4e0a\u5468\u63d0\u5347 ${point.trend}%` : `\u8f83\u4e0a\u5468\u4e0b\u964d ${Math.abs(point.trend)}%`,
    },
    description: isGraph
      ? '\u638c\u63e1\u56fe\u7684\u5b58\u50a8\u65b9\u5f0f\uff0c\u5e76\u80fd\u4f7f\u7528\u6df1\u5ea6\u4f18\u5148\u4e0e\u5e7f\u5ea6\u4f18\u5148\u7b97\u6cd5\u89e3\u51b3\u904d\u5386\u95ee\u9898\u3002'
      : isSubnet
        ? '\u6839\u636e IP \u5730\u5740\u548c\u5b50\u7f51\u63a9\u7801\u8ba1\u7b97\u7f51\u7edc\u53f7\u3001\u4e3b\u673a\u8303\u56f4\u4e0e\u5e7f\u64ad\u5730\u5740\u3002'
        : `\u7406\u89e3${point.name}\u7684\u6838\u5fc3\u6982\u5ff5\uff0c\u5e76\u5c06\u5b9a\u4e49\u3001\u6027\u8d28\u4e0e\u5178\u578b\u9898\u578b\u8fde\u63a5\u8d77\u6765\u3002`,
    metrics: {
      accuracy: Math.max(48, mastery - 3),
      exercises: 36,
      correctCount: Math.round(36 * Math.max(48, mastery - 3) / 100),
      wrongCount: 36 - Math.round(36 * Math.max(48, mastery - 3) / 100),
      studyMinutes: 142,
      reviewCount: 4,
      confidence: Math.max(45, mastery - 6),
    },
    prerequisites: isGraph
      ? [
          { id: 'ds-linear-list', name: '\u7ebf\u6027\u8868', mastery: 88, status: 'mastered' },
          { id: 'ds-binary-tree', name: '\u4e8c\u53c9\u6811', mastery: 74, status: 'learning' },
        ]
      : [
          { id: `${point.id}-basic`, name: `${point.name}\u57fa\u7840\u6982\u5ff5`, mastery: Math.min(94, mastery + 12), status: 'mastered' },
        ],
    history: [
      { date: dateOffset(-28), mastery: Math.max(35, mastery - 19), accuracy: Math.max(38, mastery - 22), studyMinutes: 35, event: '\u9996\u6b21\u5b66\u4e60' },
      { date: dateOffset(-20), mastery: Math.max(40, mastery - 13), accuracy: Math.max(42, mastery - 15), studyMinutes: 42, event: '\u5b8c\u6210\u57fa\u7840\u7ec3\u4e60' },
      { date: dateOffset(-12), mastery: Math.max(45, mastery - 7), accuracy: Math.max(46, mastery - 9), studyMinutes: 38, event: '\u9519\u9898\u8bb2\u89e3' },
      { date: dateOffset(-4), mastery, accuracy: Math.max(48, mastery - 3), studyMinutes: 27, event: '\u9636\u6bb5\u6d4b\u9a8c' },
    ],
    reviewPlan: [
      { id: `${point.id}-review-1`, date: dateOffset(1), title: '\u56de\u987e\u6838\u5fc3\u6982\u5ff5', type: 'review', durationMinutes: 15, status: 'pending' },
      { id: `${point.id}-review-2`, date: dateOffset(3), title: '\u5b8c\u6210\u9488\u5bf9\u6027\u7ec3\u4e60', type: 'exercise', durationMinutes: 25, status: 'pending' },
      { id: `${point.id}-review-3`, date: dateOffset(7), title: '\u9636\u6bb5\u638c\u63e1\u5ea6\u6d4b\u9a8c', type: 'quiz', durationMinutes: 20, status: 'pending' },
    ],
    recommendedExercises: [
      { id: `${point.id}-exercise-1`, title: `${point.name}\u57fa\u7840\u5de9\u56fa`, type: 'choice', difficulty: 'basic', estimatedMinutes: 10, reason: '\u5148\u786e\u4fdd\u6982\u5ff5\u548c\u57fa\u672c\u6b65\u9aa4\u51c6\u786e' },
      { id: `${point.id}-exercise-2`, title: `${point.name}\u5178\u578b\u9898`, type: 'practice', difficulty: 'medium', estimatedMinutes: 20, reason: '\u9488\u5bf9\u5f53\u524d\u9519\u9898\u7c7b\u578b\u5b9a\u5411\u8bad\u7ec3' },
      { id: `${point.id}-exercise-3`, title: `${point.name}\u7efc\u5408\u5e94\u7528`, type: 'challenge', difficulty: 'advanced', estimatedMinutes: 30, reason: '\u5efa\u7acb\u4e0e\u524d\u7f6e\u77e5\u8bc6\u7684\u7efc\u5408\u8054\u7cfb' },
    ],
  };
}

function normalizeUser(source, fallback) {
  const nested = pick(source, ['user', 'student', 'userInfo', 'profile'], source);
  const safeFallback = isObject(fallback) ? fallback : {};
  return {
    id: String(pick(nested, ['id', 'userId', 'studentId', 'uid'], safeFallback.id ?? '')),
    name: pick(nested, ['name', 'realName', 'nickname', 'username', 'studentName'], safeFallback.name ?? ''),
    avatar: pick(nested, ['avatar', 'avatarUrl', 'headImage', 'profileImage'], safeFallback.avatar ?? ''),
    university: pick(nested, ['university', 'school', 'collegeName'], safeFallback.university ?? ''),
    major: pick(nested, ['major', 'majorName', 'specialty'], safeFallback.major ?? ''),
    grade: pick(nested, ['grade', 'gradeName', 'year'], safeFallback.grade ?? ''),
    bio: pick(nested, ['bio', 'description', 'learningGoal', 'goal'], safeFallback.bio ?? ''),
  };
}

function normalizeSummary(source, fallback) {
  const summary = pick(source, ['summary', 'overview', 'learningSummary', 'statistics', 'stats'], source);
  return {
    overallMastery: percentage(pick(summary, ['overallMastery', 'mastery', 'masteryRate', 'averageMastery'], fallback.overallMastery), fallback.overallMastery),
    masteryChange: numberValue(pick(summary, ['masteryChange', 'masteryTrend', 'weeklyChange'], fallback.masteryChange), fallback.masteryChange),
    studyDays: numberValue(pick(summary, ['studyDays', 'totalStudyDays', 'learningDays'], fallback.studyDays), fallback.studyDays),
    streakDays: numberValue(pick(summary, ['streakDays', 'continuousDays', 'consecutiveDays'], fallback.streakDays), fallback.streakDays),
    weeklyStudyMinutes: numberValue(pick(summary, ['weeklyStudyMinutes', 'weekStudyMinutes', 'weeklyMinutes'], fallback.weeklyStudyMinutes), fallback.weeklyStudyMinutes),
    weeklyGoalMinutes: numberValue(pick(summary, ['weeklyGoalMinutes', 'weekGoalMinutes'], fallback.weeklyGoalMinutes), fallback.weeklyGoalMinutes),
    weeklyGoalProgress: percentage(pick(summary, ['weeklyGoalProgress', 'goalProgress'], fallback.weeklyGoalProgress), fallback.weeklyGoalProgress),
    completedCourses: numberValue(pick(summary, ['completedCourses', 'courseCount', 'finishedCourses'], fallback.completedCourses), fallback.completedCourses),
    completedExercises: numberValue(pick(summary, ['completedExercises', 'exerciseCount', 'questionCount'], fallback.completedExercises), fallback.completedExercises),
    message: pick(summary, ['message', 'insight', 'suggestion', 'summaryText', 'summaryProfile'], fallback.message),
  };
}

function normalizeStats(source, summary, fallback) {
  const raw = collectionFrom(source, ['stats', 'statCards', 'statistics']);
  if (!raw.length) {
    const template = Array.isArray(fallback) && fallback.length
      ? fallback
      : [
          { key: 'studyTime', label: '\u672c\u5468\u5b66\u4e60', value: summary.weeklyStudyMinutes, unit: '\u5206\u949f', change: 0 },
          { key: 'mastery', label: '\u7efc\u5408\u638c\u63e1\u5ea6', value: summary.overallMastery, unit: '%', change: summary.masteryChange },
          { key: 'streak', label: '\u8fde\u7eed\u5b66\u4e60', value: summary.streakDays, unit: '\u5929', change: 0 },
          { key: 'exercises', label: '\u7d2f\u8ba1\u7ec3\u4e60', value: summary.completedExercises, unit: '\u9898', change: 0 },
        ];
    return template.map((item) => {
      if (item.key === 'studyTime') return { ...item, value: summary.weeklyStudyMinutes };
      if (item.key === 'mastery') return { ...item, value: summary.overallMastery, change: summary.masteryChange };
      if (item.key === 'streak') return { ...item, value: summary.streakDays };
      if (item.key === 'exercises') return { ...item, value: summary.completedExercises };
      return item;
    });
  }

  return raw.map((item, index) => ({
    key: String(pick(item, ['key', 'id', 'type'], `stat-${index + 1}`)),
    label: pick(item, ['label', 'name', 'title'], '\u5b66\u4e60\u6307\u6807'),
    value: numberValue(pick(item, ['value', 'count', 'amount'], 0)),
    unit: pick(item, ['unit', 'suffix'], ''),
    change: numberValue(pick(item, ['change', 'trend', 'growth'], 0)),
  }));
}

function normalizeDimensions(source, fallback) {
  const raw = collectionFrom(source, ['dimensions', 'abilities', 'abilityDimensions', 'radarData']);
  if (!raw.length) return fallback ? clone(fallback) : [];

  return raw.map((item, index) => ({
    key: String(pick(item, ['key', 'id', 'code'], `dimension-${index + 1}`)),
    label: pick(item, ['label', 'name', 'dimensionName'], `\u7ef4\u5ea6 ${index + 1}`),
    value: percentage(pick(item, ['value', 'score', 'mastery', 'rate'], 0)),
    max: numberValue(pick(item, ['max', 'fullMark', 'maximum'], 100), 100),
  }));
}

/**
 * 当后端未直接提供 dimensions 时，从 knowledge 列表派生雷达图维度。
 */
function deriveDimensionsFromKnowledge(source, knowledgeRaw, fallback) {
  const dims = normalizeDimensions(source, fallback);
  const hasRealDimensions = collectionFrom(source, ['dimensions', 'abilities', 'abilityDimensions', 'radarData']).length > 0;
  if (hasRealDimensions) return dims;

  if (!knowledgeRaw || !knowledgeRaw.length) return fallback ? clone(fallback) : [];

  const total = knowledgeRaw.length;
  const avgMastery = knowledgeRaw.reduce((sum, k) => sum + numberValue(pick(k, ['masteryScore', 'mastery', 'score'], 0), 0), 0) / total;
  const avgConfidence = knowledgeRaw.reduce((sum, k) => sum + numberValue(pick(k, ['confidence', 'confidenceScore'], 0), 0), 0) / total;
  const improvingCount = knowledgeRaw.filter((k) => String(pick(k, ['trend'], '')).toUpperCase() === 'IMPROVING').length;
  const stableCount = knowledgeRaw.filter((k) => {
    const t = String(pick(k, ['trend'], '')).toUpperCase();
    return t === 'STABLE' || t === 'IMPROVING';
  }).length;
  const masteredCount = knowledgeRaw.filter((k) => {
    const status = String(pick(k, ['masteryStatus', 'status'], '')).toUpperCase();
    return status === 'MASTERED' || status === 'BASIC_MASTERY';
  }).length;
  const totalEvidence = knowledgeRaw.reduce((sum, k) => sum + numberValue(pick(k, ['evidenceCount'], 0), 0), 0);

  return [
    { key: 'mastery', label: '\u77e5\u8bc6\u638c\u63e1', value: Math.round(avgMastery * 100), max: 100 },
    { key: 'confidence', label: '\u638c\u63e1\u4fe1\u5fc3', value: Math.round(avgConfidence * 100), max: 100 },
    { key: 'progress', label: '\u8fdb\u6b65\u8d8b\u52bf', value: Math.round(improvingCount / total * 100), max: 100 },
    { key: 'engagement', label: '\u5b66\u4e60\u6295\u5165', value: Math.min(100, Math.round(totalEvidence / total * 5)), max: 100 },
    { key: 'coverage', label: '\u77e5\u8bc6\u8986\u76d6', value: Math.round(masteredCount / total * 100), max: 100 },
    { key: 'stability', label: '\u638c\u63e1\u7a33\u5b9a', value: Math.round(stableCount / total * 100), max: 100 },
  ];
}

function trendNumber(value) {
  if (typeof value === 'string') {
    const upper = value.toUpperCase();
    if (upper === 'IMPROVING') return 5;
    if (upper === 'DECLINING') return -5;
    if (upper === 'STABLE') return 0;
  }
  return numberValue(value, 0);
}

function normalizeKnowledgeNode(item, index, fallbackSubject = '') {
  const mastery = masteryPercentage(pick(item, ['mastery', 'masteryScore', 'masteryRate', 'score', 'progress', 'value'], 0));
  const children = collectionFrom(item, ['children', 'knowledgePoints', 'nodes', 'items']);
  const id = pick(item, ['id', 'kpId', 'knowledgePointId', 'pointId', 'knowledgeId', 'code'], `knowledge-${index + 1}`);
  const name = pick(item, ['name', 'knowledgePointName', 'pointName', 'title'], `\u77e5\u8bc6\u70b9 ${id}`);
  const subject = pick(item, ['subject', 'subjectName', 'courseName', 'category'], fallbackSubject);

  return {
    id: String(id),
    name,
    subject,
    mastery,
    status: statusFrom(pick(item, ['masteryStatus', 'status', 'state'], ''), mastery),
    trend: trendNumber(pick(item, ['trend', 'change', 'masteryChange'], 0)),
    type: pick(item, ['type', 'nodeType'], children.length ? 'subject' : 'knowledgePoint'),
    parentId: pick(item, ['parentId', 'parentKnowledgeId'], null),
    children: children.map((child, childIndex) => normalizeKnowledgeNode(child, childIndex, subject)),
  };
}

function normalizeKnowledgeTree(source, fallback) {
  const raw = collectionFrom(source, ['knowledgeTree', 'knowledge', 'knowledgeStatus', 'knowledgeStatuses', 'knowledgePoints', 'statuses', 'records', 'list', 'items']);
  if (!raw.length) return fallback ? clone(fallback) : [];

  const nodes = raw.map((item, index) => normalizeKnowledgeNode(item, index));
  if (nodes.some((node) => node.children.length)) return nodes;

  const nodeMap = new Map(nodes.map((node) => [String(node.id), node]));
  const roots = [];
  let hasParentLinks = false;
  nodes.forEach((node) => {
    const parent = node.parentId !== null ? nodeMap.get(String(node.parentId)) : null;
    if (parent) {
      hasParentLinks = true;
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });
  if (hasParentLinks) return roots;

  const grouped = new Map();
  nodes.forEach((node) => {
    const subject = node.subject || '\u5176\u4ed6';
    if (!grouped.has(subject)) grouped.set(subject, []);
    grouped.get(subject).push(node);
  });

  if (grouped.size <= 1) return nodes;
  return [...grouped.entries()].map(([subject, children], index) => ({
    id: `subject-${index + 1}`,
    name: subject,
    subject,
    mastery: Math.round(children.reduce((total, child) => total + child.mastery, 0) / children.length),
    status: 'learning',
    trend: Math.round(children.reduce((total, child) => total + child.trend, 0) / children.length),
    type: 'subject',
    parentId: null,
    children,
  }));
}

/**
 * 当后端未直接提供 summary 时，从 knowledge 列表和 profile 字段派生摘要。
 */
function deriveSummaryFromProfile(source, knowledgeRaw, fallback) {
  const summary = normalizeSummary(source, fallback);

  if (knowledgeRaw && knowledgeRaw.length) {
    const avgMastery = knowledgeRaw.reduce((sum, k) => sum + numberValue(pick(k, ['masteryScore', 'mastery', 'score'], 0), 0), 0) / knowledgeRaw.length;
    if (avgMastery > 0) summary.overallMastery = Math.round(avgMastery * 100);

    const totalEvidence = knowledgeRaw.reduce((sum, k) => sum + numberValue(pick(k, ['evidenceCount'], 0), 0), 0);
    if (totalEvidence > 0) summary.completedExercises = totalEvidence;
  }

  if (source.summaryProfile && summary.message === fallback.message) {
    summary.message = source.summaryProfile;
  }

  return summary;
}

/**
 * 当后端未直接提供 timeline 时，从 recentConfusions 和 recentFocus 派生学习轨迹。
 */
function deriveTimelineFromProfile(source, fallback) {
  const existing = collectionFrom(source, ['timeline', 'learningTimeline', 'recentActivities', 'activities', 'learningRecords']);
  if (existing.length) return normalizeTimeline(source, fallback);

  const confusions = collectionFrom(source, ['recentConfusions', 'confusions', 'confusionPoints'], []);
  const focus = collectionFrom(source, ['recentFocus', 'focusPoints', 'focus'], []);

  const entries = [];

  confusions.forEach((item, index) => {
    const kpId = pick(item, ['kpId', 'knowledgePointId', 'pointId'], `kp-${index}`);
    const detail = pick(item, ['detail', 'description', 'content'], '');
    const time = pick(item, ['lastOccurredAt', 'occurredAt', 'time', 'date'], '');
    entries.push({
      id: `confusion-${kpId}-${index}`,
      type: 'review',
      title: `\u77e5\u8bc6\u70b9 ${kpId} \u56f0\u60d1\u53cd\u9988`,
      description: detail || '\u8fd1\u671f\u5b66\u4e60\u4e2d\u51fa\u73b0\u4e86\u56f0\u60d1',
      subject: '',
      score: null,
      time,
    });
  });

  focus.forEach((item, index) => {
    const kpId = pick(item, ['kpId', 'knowledgePointId', 'pointId'], `kp-${index}`);
    entries.push({
      id: `focus-${kpId}-${index}`,
      type: 'course',
      title: `\u5173\u6ce8\u77e5\u8bc6\u70b9 ${kpId}`,
      description: '\u8fd1\u671f\u91cd\u70b9\u5b66\u4e60\u7684\u5185\u5bb9',
      subject: '',
      score: null,
      time: '',
    });
  });

  if (source.computedAt) {
    entries.push({
      id: 'profile-computed',
      type: 'milestone',
      title: '\u753b\u50cf\u66f4\u65b0\u5b8c\u6210',
      description: '\u5b66\u4e60\u753b\u50cf\u5df2\u91cd\u65b0\u8ba1\u7b97',
      subject: '',
      score: null,
      time: source.computedAt,
    });
  }

  return entries.length ? entries : (fallback ? clone(fallback) : []);
}

function normalizeTimeline(source, fallback) {
  const raw = collectionFrom(source, ['timeline', 'learningTimeline', 'recentActivities', 'activities', 'learningRecords']);
  if (!raw.length) return fallback ? clone(fallback) : [];

  return raw.map((item, index) => ({
    id: String(pick(item, ['id', 'recordId', 'activityId'], `activity-${index + 1}`)),
    type: pick(item, ['type', 'activityType', 'recordType'], 'study'),
    title: pick(item, ['title', 'name', 'activityName'], '\u5b66\u4e60\u8bb0\u5f55'),
    description: pick(item, ['description', 'content', 'detail'], ''),
    subject: pick(item, ['subject', 'subjectName', 'courseName'], ''),
    score: pick(item, ['score', 'accuracy', 'result'], null),
    time: pick(item, ['time', 'createdAt', 'studyTime', 'date'], ''),
  }));
}

function reminderPriority(value) {
  const numericPriority = Number(value);
  if (Number.isFinite(numericPriority)) {
    if (numericPriority >= 2) return 'high';
    if (numericPriority === 1) return 'medium';
    return 'low';
  }
  return String(value || 'medium').toLowerCase();
}

function reminderStatus(item) {
  const status = pick(item, ['status', 'state'], null);
  if (status !== null) return status;
  return Number(pick(item, ['isReviewed'], 0)) === 1 ? 'completed' : 'pending';
}

function normalizeReminders(source, fallback) {
  const raw = collectionFrom(source, ['reminders', 'reviewReminders', 'reviewPlans', 'records', 'list', 'items']);
  if (!raw.length) return fallback ? clone(fallback) : [];

  return raw.map((item, index) => ({
    id: String(pick(item, ['id', 'reminderId', 'planId'], `reminder-${index + 1}`)),
    knowledgePointId: String(pick(item, ['knowledgePointId', 'pointId', 'knowledgeId'], '')),
    title: pick(item, ['title', 'name', 'knowledgePointName', 'content'], '\u77e5\u8bc6\u70b9\u590d\u4e60'),
    subject: pick(item, ['subject', 'subjectName', 'courseName'], ''),
    dueAt: pick(item, ['dueAt', 'reviewAt', 'reviewDate', 'scheduledAt', 'scheduledDate', 'date'], ''),
    priority: reminderPriority(pick(item, ['priority', 'level'], 'medium')),
    reason: pick(item, ['reason', 'description', 'suggestion', 'reminderType'], ''),
    status: reminderStatus(item),
  }));
}

function normalizePreferences(source, fallback) {
  const preferences = pick(source, ['preferences', 'learningPreferences', 'settings'], source);
  const contentModes = collectionFrom(preferences, ['preferredContentModes']);
  return {
    weeklyGoalMinutes: numberValue(pick(preferences, ['weeklyGoalMinutes', 'weekGoalMinutes'], fallback.weeklyGoalMinutes), fallback.weeklyGoalMinutes),
    preferredStudyTime: pick(preferences, ['preferredStudyTime', 'studyTime', 'preferredTime'], fallback.preferredStudyTime),
    preferredSessionMinutes: numberValue(pick(preferences, ['preferredSessionMinutes', 'sessionMinutes'], fallback.preferredSessionMinutes), fallback.preferredSessionMinutes),
    reminderEnabled: Boolean(pick(preferences, ['reminderEnabled', 'enableReminder'], fallback.reminderEnabled)),
    difficulty: pick(preferences, ['difficulty', 'difficultyPreference', 'learningPace'], fallback.difficulty),
    learningStyle: pick(
      preferences,
      ['learningStyle', 'preferredLearningStyle', 'preferredExplanationStyle'],
      contentModes.length ? contentModes.join(' + ') : fallback.learningStyle,
    ),
  };
}

function resultPayload(result) {
  return result.status === 'fulfilled' ? unwrapResponse(result.value) : null;
}

function requestSucceeded(result) {
  return result.status === 'fulfilled' && resultPayload(result) !== null && resultPayload(result) !== undefined;
}

/**
 * 获取学习档案。
 *
 * @returns {Promise<{
 *   isDemo: boolean,
 *   user: object,
 *   summary: object,
 *   stats: Array,
 *   dimensions: Array,
 *   knowledgeTree: Array,
 *   timeline: Array,
 *   reminders: Array,
 *   preferences: object
 * }>}
 */
export async function getLearningProfile(userId) {
  const normalizedUserId = userId === undefined || userId === null || userId === ''
    ? 'demo-user'
    : String(userId);
  const demo = createDemoProfile(normalizedUserId);

  if (!userId) return demo;

  const [profileResult, reminderResult] = await Promise.allSettled([
    get(PROFILE_ENDPOINT(normalizedUserId)),
    get(REVIEW_REMINDERS_ENDPOINT(normalizedUserId)),
  ]);

  const profileSource = resultPayload(profileResult);
  const reminderSource = resultPayload(reminderResult);
  const profileStatus = String(profileSource?.profileStatus || '').toUpperCase();
  const profileReady = ['READY', 'STALE'].includes(profileStatus);

  // 后端返回 NOT_READY：画像正在构建中，返回构建中状态而非 demo 数据
  if (profileSource && !profileReady && profileStatus === 'NOT_READY') {
    return {
      ...demo,
      isDemo: true,
      isNotReady: true,
      demoReason: '\u5b66\u4e60\u753b\u50cf\u6b63\u5728\u6784\u5efa\u4e2d\uff0c\u8bf7\u7a0d\u540e\u518d\u6765\u67e5\u770b\u3002',
      statusReason: profileSource?.statusReason || 'NO_PROFILE',
    };
  }

  // 画像请求失败：回退到 demo 数据
  if (!profileSource && !reminderSource) return demo;

  // 提取后端 knowledge 列表用于派生其他字段
  const knowledgeRaw = collectionFrom(profileSource || {}, ['knowledge', 'knowledgeStatus', 'knowledgeStatuses', 'knowledgePoints', 'records', 'list', 'items']);

  // isDemo 仅在画像请求失败或未就绪时为 true，复习提醒失败不影响
  const isDemo = !requestSucceeded(profileResult) || !profileReady;

  const summary = deriveSummaryFromProfile(profileSource || {}, knowledgeRaw, demo.summary);
  // 字段级回退策略：isDemo（请求失败/未就绪）时才允许 demo 数据；
  // 请求成功但后端未返回某字段 → 返回空集合/派生值，由页面展示诚实空状态，不伪造生产数据。
  const fieldFallback = (demoValue) => (isDemo ? demoValue : null);
  return {
    isDemo,
    isNotReady: false,
    demoReason: isDemo ? demo.demoReason : null,
    user: normalizeUser(profileSource || {}, isDemo ? demo.user : {}),
    summary,
    stats: normalizeStats(profileSource || {}, summary, fieldFallback(demo.stats)),
    dimensions: deriveDimensionsFromKnowledge(profileSource || {}, knowledgeRaw, fieldFallback(demo.dimensions)),
    knowledgeTree: normalizeKnowledgeTree(profileSource || {}, fieldFallback(demo.knowledgeTree)),
    timeline: deriveTimelineFromProfile(profileSource || {}, fieldFallback(demo.timeline)),
    reminders: normalizeReminders(reminderSource || profileSource || {}, fieldFallback(demo.reminders)),
    preferences: normalizePreferences(profileSource || {}, demo.preferences),
  };
}

function findKnowledgePoint(source, knowledgePointId) {
  if (!source) return null;

  // 嵌套详情容器（detail / knowledgePoint / knowledgeStatus）
  for (const containerKey of ['detail', 'knowledgePoint', 'knowledgeStatus']) {
    const container = isObject(source) ? source[containerKey] : null;
    if (!isObject(container)) continue;
    const id = pick(container, ['id', 'kpId', 'knowledgePointId', 'pointId', 'knowledgeId'], null);
    if (id !== null && String(id) === String(knowledgePointId)) return container;
  }

  // source 自身即详情（且 id 匹配，避免把整个列表响应误当详情）
  const selfId = pick(source, ['id', 'kpId', 'knowledgePointId', 'pointId', 'knowledgeId'], null);
  if (selfId !== null && String(selfId) === String(knowledgePointId)) return source;

  const collection = collectionFrom(source, ['knowledge', 'knowledgeStatus', 'knowledgeStatuses', 'knowledgePoints', 'records', 'list', 'items']);
  return collection.find((item) => String(
    pick(item, ['id', 'kpId', 'knowledgePointId', 'pointId', 'knowledgeId'], '')
  ) === String(knowledgePointId)) || null;
}

function normalizeTrend(source, fallback) {
  const rawTrend = pick(source, ['trend', 'masteryTrend', 'change', 'masteryChange'], fallback.value);
  if (typeof rawTrend === 'string') {
    const directionMap = {
      IMPROVING: 'up',
      STABLE: 'stable',
      DECLINING: 'down',
    };
    const direction = directionMap[rawTrend.toUpperCase()] || fallback.direction;
    return {
      direction,
      value: fallback.value,
      label: direction === 'up'
        ? '\u638c\u63e1\u5ea6\u6b63\u5728\u63d0\u5347'
        : direction === 'down'
          ? '\u638c\u63e1\u5ea6\u6709\u6240\u4e0b\u964d'
          : '\u638c\u63e1\u5ea6\u4fdd\u6301\u7a33\u5b9a',
    };
  }
  if (isObject(rawTrend)) {
    const value = Math.abs(numberValue(pick(rawTrend, ['value', 'change', 'rate'], fallback.value)));
    const direction = pick(rawTrend, ['direction', 'type'], fallback.direction);
    return {
      direction,
      value,
      label: pick(rawTrend, ['label', 'description'], direction === 'up' ? `\u8f83\u4e0a\u5468\u63d0\u5347 ${value}%` : `\u8f83\u4e0a\u5468\u53d8\u5316 ${value}%`),
    };
  }

  const signed = numberValue(rawTrend, fallback.direction === 'down' ? -fallback.value : fallback.value);
  const direction = signed > 0 ? 'up' : signed < 0 ? 'down' : 'stable';
  return {
    direction,
    value: Math.abs(signed),
    label: direction === 'up'
      ? `\u8f83\u4e0a\u5468\u63d0\u5347 ${Math.abs(signed)}%`
      : direction === 'down'
        ? `\u8f83\u4e0a\u5468\u4e0b\u964d ${Math.abs(signed)}%`
        : '\u4e0e\u4e0a\u5468\u6301\u5e73',
  };
}

function normalizeMetrics(source, fallback) {
  const metrics = pick(source, ['metrics', 'statistics', 'stats'], source);
  return {
    accuracy: percentage(pick(metrics, ['accuracy', 'accuracyRate', 'correctRate'], fallback.accuracy), fallback.accuracy),
    exercises: numberValue(pick(metrics, ['exercises', 'exerciseCount', 'questionCount', 'totalCount'], fallback.exercises), fallback.exercises),
    correctCount: numberValue(pick(metrics, ['correctCount', 'rightCount'], fallback.correctCount), fallback.correctCount),
    wrongCount: numberValue(pick(metrics, ['wrongCount', 'incorrectCount'], fallback.wrongCount), fallback.wrongCount),
    studyMinutes: numberValue(pick(metrics, ['studyMinutes', 'learningMinutes', 'durationMinutes'], fallback.studyMinutes), fallback.studyMinutes),
    reviewCount: numberValue(pick(metrics, ['reviewCount', 'reviews'], fallback.reviewCount), fallback.reviewCount),
    confidence: percentage(pick(metrics, ['confidence', 'confidenceScore'], fallback.confidence), fallback.confidence),
  };
}

function normalizePrerequisites(source, fallback) {
  const raw = collectionFrom(source, ['prerequisites', 'preKnowledgePoints', 'dependencies', 'preconditions']);
  if (!raw.length) return fallback ? clone(fallback) : [];
  return raw.map((item, index) => {
    const mastery = percentage(pick(item, ['mastery', 'masteryRate', 'score'], 0));
    return {
      id: String(pick(item, ['id', 'knowledgePointId', 'pointId'], `prerequisite-${index + 1}`)),
      name: pick(item, ['name', 'knowledgePointName', 'title'], '\u524d\u7f6e\u77e5\u8bc6'),
      mastery,
      status: statusFrom(pick(item, ['status', 'state'], ''), mastery),
    };
  });
}

function normalizeHistory(source, fallback) {
  const raw = collectionFrom(source, ['history', 'masteryHistory', 'learningHistory', 'trendData', 'records']);
  if (!raw.length) return fallback ? clone(fallback) : [];
  return raw.map((item) => ({
    date: pick(item, ['date', 'time', 'createdAt', 'studyDate'], ''),
    mastery: percentage(pick(item, ['mastery', 'masteryRate', 'score'], 0)),
    accuracy: percentage(pick(item, ['accuracy', 'accuracyRate', 'correctRate'], 0)),
    studyMinutes: numberValue(pick(item, ['studyMinutes', 'durationMinutes', 'duration'], 0)),
    event: pick(item, ['event', 'title', 'description', 'activityName'], '\u5b66\u4e60\u8bb0\u5f55'),
  }));
}

function normalizeReviewPlan(source, remindersSource, knowledgePointId, fallback) {
  let raw = collectionFrom(source, ['reviewPlan', 'reviewPlans', 'reviews', 'plan']);
  if (!raw.length) {
    raw = collectionFrom(remindersSource, ['reminders', 'reviewReminders', 'reviewPlans', 'records', 'list', 'items'])
      .filter((item) => {
        const pointId = pick(item, ['knowledgePointId', 'pointId', 'knowledgeId'], '');
        return !pointId || String(pointId) === String(knowledgePointId);
      });
  }
  if (!raw.length) return fallback ? clone(fallback) : [];

  return raw.map((item, index) => ({
    id: String(pick(item, ['id', 'planId', 'reminderId'], `${knowledgePointId}-review-${index + 1}`)),
    date: pick(item, ['date', 'dueAt', 'reviewAt', 'reviewDate', 'scheduledAt', 'scheduledDate'], ''),
    title: pick(item, ['title', 'name', 'content'], '\u77e5\u8bc6\u70b9\u590d\u4e60'),
    type: pick(item, ['type', 'reviewType'], 'review'),
    durationMinutes: numberValue(pick(item, ['durationMinutes', 'estimatedMinutes', 'duration'], 15), 15),
    status: reminderStatus(item),
  }));
}

function normalizeExercises(source, fallback) {
  const raw = collectionFrom(source, ['recommendedExercises', 'recommendations', 'exercises', 'practiceRecommendations']);
  if (!raw.length) return fallback ? clone(fallback) : [];
  return raw.map((item, index) => ({
    id: String(pick(item, ['id', 'exerciseId', 'questionSetId'], `exercise-${index + 1}`)),
    title: pick(item, ['title', 'name', 'exerciseName'], '\u63a8\u8350\u7ec3\u4e60'),
    type: pick(item, ['type', 'exerciseType'], 'practice'),
    difficulty: pick(item, ['difficulty', 'level'], 'medium'),
    estimatedMinutes: numberValue(pick(item, ['estimatedMinutes', 'durationMinutes'], 15), 15),
    reason: pick(item, ['reason', 'description', 'recommendReason'], ''),
  }));
}

/**
 * 获取单个知识点的掌握度详情。
 */
export async function getKnowledgePointDetail(userId, knowledgePointId) {
  const normalizedUserId = userId === undefined || userId === null || userId === ''
    ? 'demo-user'
    : String(userId);
  const normalizedPointId = knowledgePointId === undefined || knowledgePointId === null || knowledgePointId === ''
    ? 'calculus-derivative'
    : String(knowledgePointId);
  const demo = createDemoDetail(normalizedPointId);

  if (!userId || !knowledgePointId) return demo;

  const [knowledgeResult, reminderResult] = await Promise.allSettled([
    get(KNOWLEDGE_STATUS_ENDPOINT(normalizedUserId), {
      kpId: normalizedPointId,
    }),
    get(REVIEW_REMINDERS_ENDPOINT(normalizedUserId), {
      knowledgePointId: normalizedPointId,
    }),
  ]);

  const knowledgeSource = resultPayload(knowledgeResult);
  const reminderSource = resultPayload(reminderResult);
  const pointSource = findKnowledgePoint(knowledgeSource, normalizedPointId);

  // 后端返回 NOT_READY、知识点不存在、或 kpId 非数字（演示树字符串 ID）时，
  // 后端 /knowledge-status 只接受数字 kpId；字符串 ID 请求必然失败。
  // 统一标记 isNotReady（画像构建中），而不是 isDemo（联调示例数据）。
  if (!pointSource) {
    const profileStatus = String(knowledgeSource?.profileStatus || '').toUpperCase();
    const numericKpId = /^\d+$/.test(normalizedPointId);
    if (profileStatus === 'NOT_READY' || !numericKpId || !requestSucceeded(knowledgeResult)) {
      return { ...demo, isNotReady: true, demoReason: '\u5b66\u4e60\u753b\u50cf\u6b63\u5728\u6784\u5efa\u4e2d\u3002' };
    }
    return demo;
  }

  const mastery = masteryPercentage(pick(pointSource, ['mastery', 'masteryScore', 'masteryRate', 'score', 'progress'], demo.mastery), demo.mastery);
  const isDemo = !requestSucceeded(knowledgeResult);
  const evidenceCount = numberValue(pick(pointSource, ['evidenceCount'], 0), 0);
  // 字段级回退策略：isDemo（请求失败/未就绪）时才允许 demo 数据；
  // 请求成功但后端未返回某字段 → 返回空集合，由页面展示诚实空状态，不伪造生产数据。
  const fieldFallback = (demoValue) => (isDemo ? demoValue : null);

  return {
    isDemo,
    isNotReady: false,
    demoReason: isDemo ? demo.demoReason : null,
    id: String(pick(pointSource, ['id', 'kpId', 'knowledgePointId', 'pointId', 'knowledgeId'], normalizedPointId)),
    name: pick(pointSource, ['name', 'knowledgePointName', 'pointName', 'title'], demo.name),
    subject: pick(pointSource, ['subject', 'subjectName', 'courseName'], demo.subject),
    mastery,
    status: statusFrom(pick(pointSource, ['masteryStatus', 'status', 'state'], ''), mastery),
    trend: normalizeTrend(pointSource, demo.trend),
    description: pick(pointSource, ['description', 'content', 'introduction', 'summary'], demo.description),
    metrics: {
      ...normalizeMetrics(pointSource, demo.metrics),
      exercises: evidenceCount || normalizeMetrics(pointSource, demo.metrics).exercises,
      correctCount: evidenceCount > 0 ? Math.round(evidenceCount * mastery / 100) : normalizeMetrics(pointSource, demo.metrics).correctCount,
      wrongCount: evidenceCount > 0 ? evidenceCount - Math.round(evidenceCount * mastery / 100) : normalizeMetrics(pointSource, demo.metrics).wrongCount,
    },
    prerequisites: normalizePrerequisites(pointSource, fieldFallback(demo.prerequisites)),
    history: normalizeHistory(pointSource, fieldFallback(demo.history)),
    reviewPlan: normalizeReviewPlan(pointSource, reminderSource || {}, normalizedPointId, fieldFallback(demo.reviewPlan)),
    recommendedExercises: normalizeExercises(pointSource, fieldFallback(demo.recommendedExercises)),
  };
}

function createEventId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `learning-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function identifier(value, fallback) {
  const normalized = String(value || fallback || '')
    .replace(/[^A-Za-z0-9._:-]/g, '-')
    .slice(0, 64);
  return normalized || fallback;
}

/**
 * 向 M6 写入一条符合 backend-M6 1.0 契约的学习行为。
 */
export async function saveLearningEvent(userId, event) {
  const numericUserId = Number(userId);
  if (!Number.isInteger(numericUserId) || numericUserId < 1) {
    throw new Error('记录学习行为需要 userId');
  }
  if (!event?.eventType || !Object.values(LEARNING_EVENT_TYPES).includes(event.eventType)) {
    throw new Error('eventType 不合法');
  }

  const payload = {
    eventId: event.eventId || createEventId(),
    userId: numericUserId,
    eventType: event.eventType,
    sourceModule: event.sourceModule,
    occurredAt: event.occurredAt || new Date().toISOString(),
    schemaVersion: '1.0',
    data: isObject(event.data) ? event.data : {},
  };
  const kpId = Number(event.kpId ?? event.knowledgePointId);
  if (Number.isInteger(kpId) && kpId > 0) payload.kpId = kpId;
  if (event.sessionId) payload.sessionId = identifier(event.sessionId, 'session');
  if (event.traceId) payload.traceId = identifier(event.traceId, 'trace');

  const response = await post(LEARNING_EVENTS_ENDPOINT(String(userId)), payload);
  return unwrapResponse(response);
}

export async function markReviewReminder(userId, reminderId, notes = '') {
  const numericUserId = Number(userId);
  const numericReminderId = Number(reminderId);
  if (!Number.isInteger(numericUserId) || numericUserId < 1) {
    throw new Error('标记复习需要登录用户 ID');
  }
  if (!Number.isInteger(numericReminderId) || numericReminderId < 1) {
    throw new Error('复习提醒 ID 不合法');
  }

  const response = await post(MARK_REVIEWED_ENDPOINT(numericUserId), {
    reminderId: numericReminderId,
    notes: String(notes || '').slice(0, 500),
  });
  return unwrapResponse(response);
}

/**
 * 非阻塞记录课堂提问。M8 是形象层，事件按正式契约归属 M4。
 */
export async function recordQuestionContext({
  userId,
  courseId,
  knowledgePointId,
  sessionId,
  chapterId,
}) {
  if (userId === undefined || userId === null || userId === '') {
    return { synced: false, reason: 'missing-user-id' };
  }

  try {
    const result = await saveLearningEvent(userId, {
      eventType: LEARNING_EVENT_TYPES.LECTURE_INTERACT,
      sourceModule: 'M4',
      knowledgePointId,
      sessionId,
      data: {
        lectureId: identifier(courseId, 'lecture'),
        chapterId: identifier(chapterId || knowledgePointId || 'general', 'general'),
        action: 'ask',
      },
    });
    return { synced: true, data: result };
  } catch (error) {
    if (import.meta.env.DEV) {
      console.warn('[M6] 提问上下文暂未写入:', error);
    }
    return { synced: false, reason: error?.message || 'request-failed' };
  }
}

export default {
  getLearningProfile,
  getKnowledgePointDetail,
  markReviewReminder,
  saveLearningEvent,
  recordQuestionContext,
};
