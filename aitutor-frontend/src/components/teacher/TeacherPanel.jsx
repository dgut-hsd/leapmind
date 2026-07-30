import React, { useState, useEffect } from 'react';
import ChatInput from '../chat/ChatInput';
import ChatDialog from '../chat/ChatDialog';
import CharacterViewer from './character/CharacterViewer.jsx';
import { initPptInteractivePlayer } from '@/features/chat/ppt-interactive-player.js';
import { askQuestion, synthesizeSpeech } from '@/features/chat/pptApi.js';
import { handleVoiceInterruption } from '@/features/chat/pptVoiceControl.js';
import { state } from '@/features/chat/pptState.js';
import { sharedViewer } from '@/features/vrmViewer/viewerContext.js';

const TeacherPanel = ({ dark = false }) => {
    const [messages, setMessages] = useState([]);
    const [isChatVisible, setIsChatVisible] = useState(false);
    const [isTeacherListening, setIsTeacherListening] = useState(false);

    const handleSendMessage = async (text) => {
        setIsChatVisible(true);
        // push user and a typing placeholder
        setMessages(prev => [...prev, { sender: 'user', text }, { sender: 'ai', isTyping: true }]);
        try {
            // 若正在播放讲课，则走统一的打断问答流程（内部含暂停与恢复）
            if (state.isPlaying && !state.isInterrupted) {
                const result = await handleVoiceInterruption(text);
                const answer = result?.answer || '（无回答）';
                setMessages(prev => {
                    const next = [...prev];
                    const idx = next.findIndex(m => m.isTyping);
                    if (idx !== -1) next[idx] = { sender: 'ai', text: answer };
                    else next.push({ sender: 'ai', text: answer });
                    return next;
                });
                return;
            }

            // 不在播放讲课时，按原逻辑直接问答并播报
            const courseId = state.currentCourseId || '';
            const result = await askQuestion(courseId, text);
            const answer = result?.answer || '（无回答）';
            setMessages(prev => {
                const next = [...prev];
                const idx = next.findIndex(m => m.isTyping);
                if (idx !== -1) next[idx] = { sender: 'ai', text: answer };
                else next.push({ sender: 'ai', text: answer });
                return next;
            });
            // TTS -> 驱动3D角色说话与头部动作
            try {
                const tts = await synthesizeSpeech(courseId, answer);
                if (tts) {
                    const arrayBuf = await tts.arrayBuffer();
                    const screenplay = {
                      expression: 'neutral',
                      talk: { message: answer }
                    };
                    sharedViewer?.model?.speak(arrayBuf, screenplay);
                }
            } catch (_) { /* ignore TTS errors */ }
        } catch (_) {
            setMessages(prev => {
                const next = [...prev];
                const idx = next.findIndex(m => m.isTyping);
                const fallback = '抱歉，回答失败，请稍后重试。';
                if (idx !== -1) next[idx] = { sender: 'ai', text: fallback };
                else next.push({ sender: 'ai', text: fallback });
                return next;
            });
        }
    };

    useEffect(() => { if (messages.length > 0) setIsChatVisible(true); }, [messages]);

    // 初始化PPT交互播放器（语音识别/音频播放/问答联动）
    useEffect(() => {
        initPptInteractivePlayer();
    }, []);

    return (
        <aside className={`w-1/4 flex flex-col ${dark ? 'bg-transparent border-l border-white/20' : 'bg-slate-50 border-l border-slate-200'}`}>
            <div className="flex-1 relative">
                <CharacterViewer />
            </div>
            <div className="mt-auto relative p-4">
                <audio id="responseAudio" className="hidden" />
                <ChatDialog messages={messages} isVisible={isChatVisible} onClose={() => setIsChatVisible(false)} />
                <ChatInput onSendMessage={handleSendMessage} setIsTeacherListening={setIsTeacherListening} />
            </div>
        </aside>
    );
};

export default TeacherPanel;
