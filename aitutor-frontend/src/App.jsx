import { useState } from 'react';
import PracticePage from './pages/PracticePage.jsx';
import QuestionBankPage from './pages/QuestionBankPage.jsx';
import WrongQuestionBookPage from './pages/WrongQuestionBookPage.jsx';
import StatisticsPage from './pages/StatisticsPage.jsx';
import RankingPage from './pages/RankingPage.jsx';

const navItems = [
  ['home', '🏠 首页'],
  ['bank', '题库'],
  ['practice', '开始做题'],
  ['wrong', '错题本'],
  ['stats', '学习统计'],
  ['ranking', '排行榜'],
];

export default function App() {
  const [page, setPage] = useState('practice');

  return (
    <div className="flex h-screen flex-col bg-slate-100 text-slate-800">
      <nav className="flex shrink-0 items-center gap-1 overflow-x-auto border-b border-slate-200 bg-white px-5 py-2 text-sm font-medium">
        {navItems.map(([key, label], index) => (
          <span key={key} className="flex items-center">
            {index > 0 && index < 2 && <span className="mx-3 text-slate-300">|</span>}
            <button
              type="button"
              onClick={() => setPage(key)}
              className={`whitespace-nowrap rounded-xl px-4 py-2 transition-colors ${
                page === key
                  ? 'bg-indigo-100 text-indigo-600'
                  : 'text-slate-600 hover:bg-slate-100 hover:text-indigo-600'
              }`}
            >
              {label}
            </button>
          </span>
        ))}
      </nav>

      <main className="min-h-0 flex-1 overflow-y-auto p-4">
        {page === 'home' && (
          <section className="mx-auto mt-16 max-w-3xl rounded-2xl bg-white p-12 text-center shadow-sm">
            <h1 className="text-2xl font-bold text-slate-800">LeapMind 练习中心</h1>
            <p className="mt-3 text-slate-500">选择题库或开始做题，进入你的学习空间。</p>
          </section>
        )}
        {page === 'bank' && <QuestionBankPage onStartPractice={() => setPage('practice')} />}
        {page === 'practice' && (
          <PracticePage onBack={() => setPage('bank')} onAskAI={() => {}} />
        )}
        {page === 'wrong' && (
          <WrongQuestionBookPage
            onRedo={() => setPage('practice')}
            onExplain={() => {}}
          />
        )}
        {page === 'stats' && <StatisticsPage />}
        {page === 'ranking' && <RankingPage />}
      </main>
    </div>
  );
}
