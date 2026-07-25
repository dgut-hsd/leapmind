import { defineConfig, loadEnv } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const javaApi = env.JAVA_API_TARGET  || 'http://localhost:8080'

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      proxy: {
        // PPT & 业务（Java 后端）
        '/api/speech': {
          target: javaApi,
          changeOrigin: true,
          secure: false,
        },
        //新页面不再使用这两个接口
        // '/api/projects': {
        //   target: javaApi,
        //   changeOrigin: true,
        //   secure: false,
        // },
        // '/auth': {
        //   target: javaApi,
        //   changeOrigin: true,
        //   secure: false,
        // },
        // 用户认证接口（Java 后端）
        '/api/auth': {
          target: javaApi,
          changeOrigin: true,
          secure: false,
        },
        // 教育阶段接口（Java 后端）
        '/api/education': {
          target: javaApi,
          changeOrigin: true,
          secure: false,
        },
        // 课程接口（Java 后端）
        '/api/courses': {
          target: javaApi,
          changeOrigin: true,
          secure: false,
        },
        // 语音问答（Java 后端）
        '/api/voice-chat': {
          target: javaApi,
          changeOrigin: true,
          secure: false,
        },
      },
    },
  }
})
