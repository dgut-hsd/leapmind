import { describe, test, expect, jest, beforeEach } from '@jest/globals';

/**
 * M6 FRONTEND FINAL：service 层业务行为测试（M6-1 ~ M6-9）。
 * 环境为 node（无 DOM），测试服务规范化/回退/字段契约，不做组件渲染。
 * 核心不变量：请求成功但后端字段缺失 → 空集合（不伪造 demo 数据）；
 * 请求失败 → 明确 isDemo 标记的演示数据；数值归一无 NaN/越界。
 * 使用 jest.unstable_mockModule（纯 ESM 无 babel transform 环境）。
 */

jest.unstable_mockModule('../src/services/api.js', () => ({
  get: jest.fn(),
  post: jest.fn(),
}));

const { getLearningProfile, getKnowledgePointDetail, markReviewReminder } = await import('../src/services/learningProfileService.js');
const { get, post } = await import('../src/services/api.js');

const apiGet = get;
const apiPost = post;

function fulfilled(response) {
  return Promise.resolve({ data: response, status: 200, statusText: 'OK' });
}

beforeEach(() => {
  jest.clearAllMocks();
});

// ── M6-1: profile API 成功 → 渲染所需全部 section 字段 ────────
describe('M6-1 profile API 成功返回所需字段', () => {
  test('成功响应返回 radar/knowledgeTree/timeline/reminders/stats 全部 section', async () => {
    const backend = {
      profileStatus: 'READY',
      user: { id: 1, name: '张三', major: '计算机' },
      dimensions: [
        { key: 'concept', label: '概念理解', value: 80 },
        { key: 'application', label: '知识应用', value: 70 },
      ],
      knowledgeStatus: [
        { kpId: 1, name: '极限', subject: '高数', mastery: 0.9, masteryStatus: 'MASTERED' },
        { kpId: 2, name: '导数', subject: '高数', mastery: 0.6, masteryStatus: 'LEARNING' },
      ],
      recentActivities: [
        { id: 'a1', type: 'course', title: '完成极限练习', description: '12 题', time: '2026-08-01' },
      ],
      summaryProfile: '本周节奏稳定',
    };
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('review-reminders')) {
        return fulfilled({
          reminders: [
            { id: 1, knowledgePointId: 2, title: '复习导数', dueAt: '2026-08-10', priority: 1, reason: '待巩固' },
          ],
        });
      }
      return fulfilled(backend);
    });

    const profile = await getLearningProfile(1);
    expect(profile.isDemo).toBe(false);
    expect(profile.dimensions.length).toBe(2);
    expect(profile.knowledgeTree.length).toBeGreaterThan(0);
    expect(profile.timeline.length).toBe(1);
    expect(profile.reminders.length).toBe(1);
    expect(profile.stats.length).toBeGreaterThan(0);
  });
});

// ── M6-2: 空 profile 数据 → 诚实空状态 ────────────────────────
describe('M6-2 请求成功但字段缺失 → 空集合而非 demo 伪造', () => {
  test('成功但无 timeline/knowledge/reminders → 返回空数组（不注入 demo 数据）', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('review-reminders')) return fulfilled({ reminders: [] });
      return fulfilled({ profileStatus: 'READY', user: { id: 1, name: '张三' } });
    });

    const profile = await getLearningProfile(1);
    expect(profile.isDemo).toBe(false);
    expect(profile.timeline).toEqual([]);
    expect(profile.knowledgeTree).toEqual([]);
    expect(profile.reminders).toEqual([]);
    expect(profile.dimensions).toEqual([]);
  });

  test('详情页：成功但 reviewPlan/history/prerequisites 缺失 → 空数组', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('knowledge-status')) {
        return fulfilled({
          profileStatus: 'READY',
          knowledge: [
            { kpId: 7, name: '二叉树', subject: '数据结构', mastery: 0.74, masteryStatus: 'LEARNING' },
          ],
        });
      }
      return fulfilled({ reminders: [] });
    });

    const detail = await getKnowledgePointDetail(1, 7);
    expect(detail.isDemo).toBe(false);
    expect(detail.reviewPlan).toEqual([]);
    expect(detail.history).toEqual([]);
    expect(detail.prerequisites).toEqual([]);
    expect(detail.recommendedExercises).toEqual([]);
  });
});

// ── M6-3: API 错误不崩溃 → isDemo 标记的演示数据 ───────────────
describe('M6-3 API 错误不崩溃页面', () => {
  test('profile 请求失败 → 返回 isDemo=true 的演示数据（不抛异常）', async () => {
    apiGet.mockImplementation(() => Promise.reject(new Error('network down')));
    const profile = await getLearningProfile(1);
    expect(profile.isDemo).toBe(true);
    expect(profile.knowledgeTree.length).toBeGreaterThan(0);
    expect(profile.reminders.length).toBeGreaterThan(0);
  });

  test('详情请求失败 → isDemo=true 演示数据', async () => {
    apiGet.mockImplementation(() => Promise.reject(new Error('network down')));
    const detail = await getKnowledgePointDetail(1, 7);
    expect(detail.isDemo).toBe(true);
    expect(detail.name).toBeTruthy();
  });
});

// ── M6-4: mastery 数值一致性 ───────────────────────────────────
describe('M6-4 mastery 数值归一一致', () => {
  test('0-1 小数与 0-100 百分数统一为百分制', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('review-reminders')) return fulfilled({ reminders: [] });
      return fulfilled({
        profileStatus: 'READY',
        knowledgeStatus: [
          { kpId: 1, name: 'A', mastery: 0.85, masteryStatus: 'MASTERED' },
          { kpId: 2, name: 'B', mastery: 92, masteryStatus: 'MASTERED' },
        ],
      });
    });
    const profile = await getLearningProfile(1);
    // knowledgeTree 可能为 [subject, ...]（有 children）或扁平叶子节点
    const nodes = profile.knowledgeTree.flatMap((s) => (s.children && s.children.length ? s.children : [s]));
    const values = nodes.map((n) => n.mastery);
    expect(values).toContain(85);
    expect(values).toContain(92);
    expect(values.every((v) => Number.isFinite(v))).toBe(true);
  });
});

// ── M6-5: review reminders 派生/展示正确 ───────────────────────
describe('M6-5 review reminders 派生正确', () => {
  test('reminder 字段映射：knowledgePointId/title/dueAt/reason 保留', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('review-reminders')) {
        return fulfilled({
          reminders: [
            { id: 5, knowledgePointId: 3, title: '复习图遍历', dueAt: '2026-08-11', priority: 2, reason: '正确率低于 60%' },
          ],
        });
      }
      return fulfilled({ profileStatus: 'READY' });
    });
    const profile = await getLearningProfile(1);
    const reminder = profile.reminders[0];
    expect(reminder.knowledgePointId).toBe('3');
    expect(reminder.title).toBe('复习图遍历');
    expect(reminder.reason).toBe('正确率低于 60%');
    expect(reminder.priority).toBe('high');
  });
});

// ── M6-6: 知识点详情接收正确 route/id ──────────────────────────
describe('M6-6 知识点详情 id 传递', () => {
  test('请求携带正确 kpId 且返回对应该知识点的数据', async () => {
    apiGet.mockImplementation((endpoint, params) => {
      if (endpoint.includes('knowledge-status')) {
        expect(params).toEqual({ kpId: '42' });
        return fulfilled({
          profileStatus: 'READY',
          knowledge: [{ kpId: 42, name: '图', subject: '数据结构', mastery: 0.57, masteryStatus: 'WEAK' }],
        });
      }
      return fulfilled({ reminders: [] });
    });
    const detail = await getKnowledgePointDetail(1, '42');
    expect(detail.id).toBe('42');
    expect(detail.name).toBe('图');
    expect(detail.status).toBe('weak');
  });
});

// ── M6-7: review plan 空状态 ───────────────────────────────────
describe('M6-7 review plan 空状态', () => {
  test('请求成功但无复习计划 → reviewPlan 为空数组（UI 展示空状态）', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('knowledge-status')) {
        return fulfilled({ profileStatus: 'READY', knowledge: [{ kpId: 9, name: 'X', mastery: 0.5, masteryStatus: 'LEARNING' }] });
      }
      return fulfilled({ reminders: [] });
    });
    const detail = await getKnowledgePointDetail(1, 9);
    expect(detail.reviewPlan).toEqual([]);
  });
});

// ── M6-8: 知识树选择不破坏当前 id ──────────────────────────────
describe('M6-8 知识树节点 id 保持', () => {
  test('嵌套节点 id/knowledgePointId 保留且可导航', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('review-reminders')) return fulfilled({ reminders: [] });
      return fulfilled({
        profileStatus: 'READY',
        knowledgeStatus: [
          { kpId: 11, name: '高数', mastery: 0.8, type: 'subject', children: [
            { kpId: 12, name: '极限', mastery: 0.9, masteryStatus: 'MASTERED' },
            { kpId: 13, name: '导数', mastery: 0.6, masteryStatus: 'REVIEW' },
          ] },
        ],
      });
    });
    const profile = await getLearningProfile(1);
    const subject = profile.knowledgeTree[0];
    expect(subject.id).toBe('11');
    const childIds = subject.children.map((c) => c.id);
    expect(childIds).toEqual(['12', '13']);
  });
});

// ── M6-9: 无 NaN/undefined 百分比 ──────────────────────────────
describe('M6-9 无 NaN/undefined 渲染值', () => {
  test('所有 mastery/百分比数值均为有限数且在 0-100 内', async () => {
    apiGet.mockImplementation((endpoint) => {
      if (endpoint.includes('review-reminders')) return fulfilled({ reminders: [] });
      return fulfilled({
        profileStatus: 'READY',
        knowledgeStatus: [
          { kpId: 1, name: 'A', mastery: 'invalid', masteryStatus: '' },
          { kpId: 2, name: 'B' },
        ],
      });
    });
    const profile = await getLearningProfile(1);
    const nodes = profile.knowledgeTree.flatMap((s) => (s.children && s.children.length ? s.children : [s]));
    nodes.forEach((n) => {
      expect(Number.isFinite(n.mastery)).toBe(true);
      expect(n.mastery).toBeGreaterThanOrEqual(0);
      expect(n.mastery).toBeLessThanOrEqual(100);
    });
  });
});

// ── markReviewReminder 契约 ────────────────────────────────────
describe('markReviewReminder 契约', () => {
  test('非法 userId/reminderId 拒绝调用（不伪造成功）', async () => {
    await expect(markReviewReminder(null, 1)).rejects.toThrow('登录用户');
    await expect(markReviewReminder(1, null)).rejects.toThrow('复习提醒');
    expect(apiPost).not.toHaveBeenCalled();
  });
});
