/**
 * VirtualTeacherDock — M4 讲课页内紧凑虚拟教师组件（M8 FINAL.1）。
 *
 * 产品原则：讲课为主，教师为辅。
 * - 桌面：侧栏紧凑区域；移动端：位于"老师"Tab 内
 * - 展示：VRM 教师（自建 viewer 实例，不持有 sharedViewer 生命周期）
 * - 语音状态使用产品词（不暴露 PCM/ReadableStream/缓冲队列等技术词）
 * - 控制由真实播放能力派生：canPause=false 时绝不显示"暂停/已暂停"
 * - 隐藏教师 ≠ 静音
 * - prefers-reduced-motion：用 state 驱动（非 ref），变化会触发渲染
 *
 * 不在本组件中渲染虚拟笔记本电脑/课件副本 —— M4 SlideViewer 是唯一课件表面。
 */
import React, { useState } from 'react';
import { Play, RotateCcw, Pause, Settings2, EyeOff, Eye, Volume2 } from 'lucide-react';
import VirtualTeacherViewer from './VirtualTeacherViewer.jsx';
import { SPEECH_STATE } from '@/features/virtualTeacher/lectureSpeechAdapter.js';

/** 产品可见状态词（技术内部状态 → 学生语言）。 */
const STATE_LABEL = {
  [SPEECH_STATE.IDLE]: '点击开始讲解',
  [SPEECH_STATE.PREPARING]: '正在准备讲解',
  [SPEECH_STATE.BUFFERING]: '正在讲解',
  [SPEECH_STATE.PLAYING]: '正在讲解',
  [SPEECH_STATE.PAUSED]: '已暂停',
  [SPEECH_STATE.ENDED]: '讲解完成',
  [SPEECH_STATE.ERROR]: '老师暂时无法发声',
  [SPEECH_STATE.AUTOPLAY_BLOCKED]: '点击开始讲解',
};

const VirtualTeacherDock = ({
  avatar,
  speechState = SPEECH_STATE.IDLE,
  canPause = false,
  onStart,
  onReplay,
  onPause,
  onResume,
  onOpenSettings,
  hidden = false,
  onToggleHidden,
  onViewerReady,
}) => {
  const [modelError, setModelError] = useState(false);

  const handleReady = (v) => {
    setModelError(false);
    onViewerReady?.(v?.model || null);
  };
  const handleError = () => {
    setModelError(true);
    onViewerReady?.(null);
  };

  // 仅流式播放真实支持暂停；预生成/兜底路径不显示暂停/已暂停
  const showPause = canPause && speechState === SPEECH_STATE.PLAYING;
  const showResume = canPause && speechState === SPEECH_STATE.PAUSED;
  const busy = speechState === SPEECH_STATE.PREPARING || speechState === SPEECH_STATE.BUFFERING;

  if (hidden) {
    return (
      <div className="flex w-full flex-col items-center gap-2 rounded-2xl border border-white/10 bg-white/[.06] p-3 text-white/70">
        <p className="text-xs">虚拟教师已隐藏，旁白音频不受影响。</p>
        <button
          type="button"
          onClick={onToggleHidden}
          className="inline-flex items-center gap-1.5 rounded-xl border border-white/15 bg-white/10 px-3 py-1.5 text-xs font-semibold transition hover:bg-white/20"
        >
          <Eye size={14} /> 显示教师
        </button>
      </div>
    );
  }

  return (
    <div className="flex w-full flex-col gap-2">
      {/* 教师视窗（紧凑，不包裹虚拟电脑/课件副本） */}
      <div className="relative h-56 w-full overflow-hidden rounded-2xl border border-white/15 bg-indigo-950/40 lg:h-64">
        {avatar?.modelUrl && (
          <VirtualTeacherViewer
            key={avatar.id}
            modelUrl={avatar.modelUrl}
            className="h-full w-full"
            onReady={handleReady}
            onError={handleError}
          />
        )}
        {!avatar?.modelUrl && (
          <div className="grid h-full w-full place-items-center text-sm text-white/70">暂无可用的教师形象</div>
        )}
        {/* 语音状态（产品词） */}
        <div className="absolute left-2 top-2 z-10 rounded-full bg-black/45 px-2.5 py-1 text-[11px] font-semibold text-white/90 backdrop-blur-sm">
          {modelError ? '教师加载失败' : STATE_LABEL[speechState] || '点击开始讲解'}
        </div>
        {/* 隐藏教师 */}
        <button
          type="button"
          onClick={onToggleHidden}
          aria-label="隐藏虚拟教师"
          className="absolute right-2 top-2 z-10 grid h-7 w-7 place-items-center rounded-full bg-black/45 text-white/85 backdrop-blur-sm transition hover:bg-black/65 motion-reduce:transition-none motion-reduce:hover:bg-black/45"
        >
          <EyeOff size={14} />
        </button>
      </div>

      {/* 教师信息 + 控制 */}
      <div className="flex items-center justify-between gap-2 px-1">
        <div className="min-w-0">
          <p className="truncate text-sm font-bold text-white/95">{avatar?.name || '虚拟教师'}</p>
          {avatar?.voiceType && (
            <p className="flex items-center gap-1 text-[11px] text-white/60">
              <Volume2 size={11} /> {avatar.voiceType}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          {showPause && (
            <button
              type="button"
              onClick={onPause}
              aria-label="暂停讲解"
              className="grid h-9 w-9 place-items-center rounded-xl border border-white/15 bg-white/10 text-white transition hover:bg-white/20 motion-reduce:transition-none motion-reduce:hover:bg-white/10"
            >
              <Pause size={16} />
            </button>
          )}
          {showResume && (
            <button
              type="button"
              onClick={onResume}
              aria-label="继续讲解"
              className="grid h-9 w-9 place-items-center rounded-xl border border-white/15 bg-white/10 text-white transition hover:bg-white/20 motion-reduce:transition-none motion-reduce:hover:bg-white/10"
            >
              <Play size={16} />
            </button>
          )}
          <button
            type="button"
            onClick={onStart}
            disabled={busy}
            aria-label="开始讲解"
            className="grid h-9 w-9 place-items-center rounded-xl bg-amber-300 text-indigo-950 transition hover:bg-amber-200 motion-reduce:transition-none motion-reduce:hover:bg-amber-300 disabled:cursor-wait disabled:opacity-60"
          >
            <Play size={16} />
          </button>
          <button
            type="button"
            onClick={onReplay}
            aria-label="重新讲解本页"
            className="grid h-9 w-9 place-items-center rounded-xl border border-white/15 bg-white/10 text-white transition hover:bg-white/20 motion-reduce:transition-none motion-reduce:hover:bg-white/10"
          >
            <RotateCcw size={15} />
          </button>
          <button
            type="button"
            onClick={onOpenSettings}
            aria-label="教师设置"
            className="grid h-9 w-9 place-items-center rounded-xl border border-white/15 bg-white/10 text-white transition hover:bg-white/20 motion-reduce:transition-none motion-reduce:hover:bg-white/10"
          >
            <Settings2 size={16} />
          </button>
        </div>
      </div>

      {/* 语音失败降级：课程始终可用 */}
      {speechState === SPEECH_STATE.ERROR && (
        <div className="rounded-xl border border-rose-200/25 bg-rose-500/15 px-3 py-2 text-[11px] leading-5 text-rose-100">
          老师暂时无法发声，你仍然可以继续查看课程内容。
          <button
            type="button"
            onClick={onReplay}
            className="ml-2 font-semibold underline decoration-rose-200/60 underline-offset-2 hover:text-white"
          >
            重新尝试
          </button>
        </div>
      )}
      {speechState === SPEECH_STATE.AUTOPLAY_BLOCKED && (
        <div className="rounded-xl border border-amber-200/25 bg-amber-500/15 px-3 py-2 text-[11px] leading-5 text-amber-100">
          浏览器阻止了自动播放，点击重新尝试开始讲解。
          <button
            type="button"
            onClick={onReplay}
            className="ml-2 font-semibold underline decoration-amber-200/60 underline-offset-2 hover:text-white"
          >
            重新尝试
          </button>
        </div>
      )}
      {modelError && (
        <div className="rounded-xl border border-white/10 bg-white/[.06] px-3 py-2 text-[11px] leading-5 text-white/60">
          3D 教师加载失败，旁白与课程内容不受影响。
        </div>
      )}
    </div>
  );
};

export default VirtualTeacherDock;
