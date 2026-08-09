/**
 * AvatarPickerDrawer — 讲课页内的教师形象选择抽屉（M8 FINAL）。
 *
 * 复用现有虚拟教师形象 API（fetchTeacherAvatars / fetchTeacherPreference / saveTeacherPreference），
 * 移除演示/开发控件（互动表现预览：微笑/思考/强调）。
 * 学生不必离开讲课页即可完成常规教师更换。
 *
 * 选择保存后由父组件驱动：音频继续播放（不重启旁白），新模型就绪后 lip-sync 自动接管。
 */
import React, { useEffect, useState } from 'react';
import { X, CloudOff } from 'lucide-react';
import {
  fetchTeacherAvatars,
  fetchTeacherPreference,
  saveTeacherPreference,
} from '@/services/virtualTeacherService.js';

const AvatarPickerDrawer = ({ open, currentAvatar, onClose, onSaved }) => {
  const [avatars, setAvatars] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [saveState, setSaveState] = useState('idle'); // idle | saving | local | error

  useEffect(() => {
    if (!open) return;
    setSelectedId(currentAvatar?.id ?? null);
    setSaveState('idle');
    let active = true;
    Promise.all([fetchTeacherAvatars(), fetchTeacherPreference()])
      .then(([list, pref]) => {
        if (!active) return;
        setAvatars(list);
        if (pref?.id) setSelectedId((cur) => cur ?? pref.id);
      })
      .catch(() => { if (active) setAvatars([]); });
    return () => { active = false; };
  }, [open, currentAvatar?.id]);

  // Escape 关闭（可访问性）
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const selected = avatars.find((a) => a.id === selectedId) || null;

  const handleSave = async () => {
    if (!selected || saveState === 'saving') return;
    setSaveState('saving');
    try {
      const result = await saveTeacherPreference(selected);
      setSaveState(result.synced ? 'idle' : 'local');
      onSaved?.(selected, result);
    } catch (_) {
      setSaveState('error');
    }
  };

  return (
    <div className="absolute inset-0 z-40" role="dialog" aria-modal="true" aria-label="选择虚拟教师">
      {/* 遮罩：点击关闭 */}
      <button
        type="button"
        aria-label="关闭教师选择"
        onClick={onClose}
        className="absolute inset-0 h-full w-full bg-black/45 backdrop-blur-sm"
      />
      {/* 抽屉 */}
      <aside className="absolute right-0 top-0 flex h-full w-[min(92vw,380px)] flex-col overflow-y-auto border-l border-white/15 bg-[#2a0e63]/95 p-5 text-white shadow-2xl backdrop-blur-xl">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h2 className="text-lg font-black">选择虚拟教师</h2>
            <p className="mt-0.5 text-xs text-purple-100/60">选择后会同步到讲课页和互动答疑组件。</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="grid h-9 w-9 place-items-center rounded-full border border-white/15 bg-white/10 transition hover:bg-white/20 motion-reduce:transition-none motion-reduce:hover:bg-white/10"
          >
            <X size={17} />
          </button>
        </div>

        {/* 头像卡片 */}
        <div className="space-y-2.5">
          {avatars.map((avatar) => {
            const selectedNow = avatar.id === selectedId;
            return (
              <button
                key={avatar.id}
                type="button"
                onClick={() => setSelectedId(avatar.id)}
                aria-pressed={selectedNow}
                className={`flex w-full items-center gap-3 rounded-2xl border p-3 text-left transition motion-reduce:transition-none ${
                  selectedNow
                    ? 'border-cyan-300 bg-cyan-300/15'
                    : 'border-white/10 bg-white/[.06] hover:bg-white/12 motion-reduce:hover:bg-white/[.06]'
                }`}
              >
                <span
                  className={`grid h-11 w-11 shrink-0 place-items-center rounded-full bg-gradient-to-br text-sm font-black ${
                    avatar.color || 'from-fuchsia-400 to-violet-500'
                  }`}
                >
                  {avatar.name?.[0] || '师'}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-bold">{avatar.name}</span>
                  <span className="mt-0.5 block truncate text-xs text-purple-100/60">{avatar.description}</span>
                  {avatar.voiceType && (
                    <span className="mt-1 block text-[10px] text-white/45">音色：{avatar.voiceType}</span>
                  )}
                </span>
                <span
                  className={`h-4 w-4 shrink-0 rounded-full border-2 ${
                    selectedNow ? 'border-cyan-300 bg-cyan-300 shadow-[0_0_12px_#67e8f9]' : 'border-white/35'
                  }`}
                />
              </button>
            );
          })}
          {avatars.length === 0 && (
            <p className="rounded-2xl border border-white/10 bg-white/[.05] p-4 text-center text-xs text-white/60">
              暂时无法加载教师形象，请稍后重试。
            </p>
          )}
        </div>

        <div className="mt-auto pt-6">
          {saveState === 'local' && (
            <p className="mb-3 flex items-center gap-2 text-xs text-amber-200">
              <CloudOff size={15} /> 后端接口尚未连通，选择已保存在当前浏览器。
            </p>
          )}
          {saveState === 'error' && <p className="mb-3 text-xs text-rose-200">保存失败，请重新登录后再试。</p>}
          <button
            type="button"
            onClick={handleSave}
            disabled={!selected || saveState === 'saving'}
            className="w-full rounded-2xl bg-gradient-to-r from-cyan-400 to-blue-500 px-5 py-3.5 font-black text-indigo-950 shadow-xl transition hover:-translate-y-0.5 motion-reduce:transition-none motion-reduce:hover:translate-y-0 disabled:cursor-wait disabled:opacity-60"
          >
            {saveState === 'saving' ? '正在保存…' : currentAvatar?.id === selected?.id ? '已设为我的虚拟教师' : '使用这个形象'}
          </button>
        </div>
      </aside>
    </div>
  );
};

export default AvatarPickerDrawer;
