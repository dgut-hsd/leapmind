import React, { useEffect, useState } from 'react';  
import GlobalStyles from './styles/GlobalStyles.jsx';
import LoginPage from './pages/LoginPage';
import LoginPage2 from './pages/LoginPage2.jsx';
import ProjectListPage from './pages/ProjectListPage';
import LecturePage from './pages/LecturePage';
import LecturePage2 from './pages/LecturePage2';
import TemHomePage from './pages/TemHomePage';
import ProfilePage from './pages/ProfilePage.jsx';
import { hasValidToken } from './utils/tokenManager';
import { checkAuth, logout } from './services/authService';

export default function App() {
    const [isChecking, setIsChecking] = useState(true);
    const [isAuthed, setIsAuthed] = useState(false);
    const [currentCourseId, setCurrentCourseId] = useState('');
    const [guestRoute, setGuestRoute] = useState('home'); // home | profile
    const [showProfile, setShowProfile] = useState(false);

    useEffect(() => {
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
        <div className={isAuthed ? "flex h-screen bg-slate-100 text-slate-800" : "w-full h-screen"}>
            <GlobalStyles />
            {isChecking ? (
                <div className="m-auto text-slate-600">检查会话中…</div>
            ) : !isAuthed ? (
                guestRoute === 'profile' ? (
                    <ProfilePage onBack={() => setGuestRoute('home')} />
                ) : (
                    // 使用登录页面
                    <LoginPage2 onLoginSuccess={handleLoginSuccess} />
                )
            ) : currentCourseId ? (
                      <LecturePage2 courseId={currentCourseId} onBack={() => setCurrentCourseId('')} />
            ) : (
                showProfile ? (
                    <ProfilePage onBack={() => setShowProfile(false)} />
                ) : (
                    <TemHomePage 
                        onEnterProject={(courseId) => setCurrentCourseId(courseId)}
                        onOpenProfile={handleOpenProfile}
                    />
                )
            )}
        </div>
    );
}