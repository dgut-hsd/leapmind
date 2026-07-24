import React, { useEffect, useState } from 'react';  
import GlobalStyles from './styles/GlobalStyles.jsx';
import LoginPage from './pages/LoginPage';
import LoginPage2 from './pages/LoginPage2.jsx';
import ProjectListPage from './pages/ProjectListPage';
import LecturePage from './pages/LecturePage';
import LecturePage2 from './pages/LecturePage2';
import TemHomePage from './pages/TemHomePage';
import ProfilePage from './pages/ProfilePage.jsx';
// M1 做题板块
import PracticePage from './pages/PracticePage.jsx';
import QuestionBankPage from './pages/QuestionBankPage.jsx';
import WrongQuestionBookPage from './pages/WrongQuestionBookPage.jsx';
import StatisticsPage from './pages/StatisticsPage.jsx';
import RankingPage from './pages/RankingPage.jsx';
import { hasValidToken } from './utils/tokenManager';
import { checkAuth, logout } from './services/authService';

const DEV_BYPASS = import.meta.env.DEV;

export default function App() {
    const [isChecking, setIsChecking] = useState(DEV_BYPASS ? false : true);
    const [isAuthed, setIsAuthed] = useState(DEV_BYPASS ? true : false);
    const [currentCourseId, setCurrentCourseId] = useState('');
    const [guestRoute, setGuestRoute] = useState('home');
    const [showProfile, setShowProfile] = useState(false);
    // M1 页面路由
    const [m1Page, setM1Page] = useState(null); // null | 'practice' | 'bank' | 'wrong' | 'stats' | 'ranking'

    useEffect(() => {
        if (DEV_BYPASS) return; // 开发模式跳过认证
        const checkSession = async () => {
            try {
                // 先检查本地是否有有效的 token
                if (!hasValidToken()) {
                    setIsAuthed(false);
                    setIsChecking(false);
                    return;
                }

                // 验证 token 是否真的有效（调用后端接口）
                const isValid = await checkAuth();
                setIsAuthed(isValid);
            } catch (error) {
                console.error('会话检查失败:', error);
                setIsAuthed(false);
            } finally {
                setIsChecking(false);
            }
        };
        checkSession();
    }, []);

    const handleLogout = () => {
        logout();
        setCurrentCourseId('');
        setIsAuthed(false);
        window.location.reload();
    };

    const handleLoginSuccess = (user) => {
        console.log('登录成功，用户信息:', user);
        setIsAuthed(true);
    };

    const handleOpenProfile = () => {
        setShowProfile(true);
    };

    return (
        <div className={isAuthed ? "flex flex-col h-screen bg-slate-100 text-slate-800" : "w-full h-screen"}>
            <GlobalStyles />
            {isChecking ? (
                <div className="m-auto text-slate-600">检查会话中…</div>
            ) : !isAuthed ? (
                guestRoute === 'profile' ? (
                    <ProfilePage onBack={() => setGuestRoute('home')} />
                ) : (
                    <LoginPage2 onLoginSuccess={handleLoginSuccess} />
                )
            ) : (
                <>
                    {/* M1 导航栏（开发预览用） */}
                    <nav className="flex items-center gap-1 px-4 py-2 bg-white border-b border-slate-200 text-xs font-medium flex-shrink-0 overflow-x-auto">
                        <NavBtn active={!m1Page} onClick={() => { setM1Page(null); setCurrentCourseId(''); setShowProfile(false); }}>
                            🏠 首页
                        </NavBtn>
                        <span className="text-slate-300">|</span>
                        <NavBtn active={m1Page === 'bank'} onClick={() => setM1Page('bank')}>
                            题库
                        </NavBtn>
                        <NavBtn active={m1Page === 'practice'} onClick={() => setM1Page('practice')}>
                            开始做题
                        </NavBtn>
                        <NavBtn active={m1Page === 'wrong'} onClick={() => setM1Page('wrong')}>
                            错题本
                        </NavBtn>
                        <NavBtn active={m1Page === 'stats'} onClick={() => setM1Page('stats')}>
                            学习统计
                        </NavBtn>
                        <NavBtn active={m1Page === 'ranking'} onClick={() => setM1Page('ranking')}>
                            排行榜
                        </NavBtn>
                    </nav>

                    {/* 内容区 */}
                    <div className="flex-1 overflow-y-auto p-4">
                        {m1Page === 'practice' ? (
                            <PracticePage
                                onBack={() => setM1Page(null)}
                                onAskAI={(q) => console.log('问AI:', q)}
                            />
                        ) : m1Page === 'bank' ? (
                            <QuestionBankPage onStartPractice={() => setM1Page('practice')} />
                        ) : m1Page === 'wrong' ? (
                            <WrongQuestionBookPage
                                onRedo={(ids) => { console.log('重做:', ids); setM1Page('practice'); }}
                                onExplain={(q) => console.log('讲解:', q)}
                            />
                        ) : m1Page === 'stats' ? (
                            <StatisticsPage />
                        ) : m1Page === 'ranking' ? (
                            <RankingPage />
                        ) : currentCourseId ? (
                            <LecturePage2 courseId={currentCourseId} onBack={() => setCurrentCourseId('')} />
                        ) : showProfile ? (
                            <ProfilePage onBack={() => setShowProfile(false)} />
                        ) : (
                            <TemHomePage 
                                onEnterProject={(courseId) => setCurrentCourseId(courseId)}
                                onOpenProfile={handleOpenProfile}
                            />
                        )}
                    </div>
                </>
            )}
        </div>
    );
}

/** 导航按钮 */
function NavBtn({ active, onClick, children }) {
    return (
        <button
            onClick={onClick}
            className={`px-3 py-1.5 rounded-lg transition-colors cursor-pointer whitespace-nowrap
                ${active ? 'bg-indigo-100 text-indigo-600' : 'text-slate-500 hover:bg-slate-100 hover:text-slate-700'}`}
        >
            {children}
        </button>
    );
}