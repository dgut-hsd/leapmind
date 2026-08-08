package com.treepeople.leapmindtts.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring MVC 异步请求配置（StreamingResponseBody 等）。
 *
 * <p>仓库现有 {@link AsyncConfig} 仅服务于 {@code @Async} 方法执行，未配置
 * MVC 异步请求线程池。此处提供显式有界执行器：
 * <ul>
 *   <li>有界核心/最大线程数，有界队列（内存安全限制，非吞吐 SLA）；</li>
 *   <li>拒绝策略采用 fail-fast {@code AbortPolicy}，避免把耗时的
 *       StreamingResponseBody 写回 servlet 请求线程（CallerRunsPolicy 的危害）；</li>
 *   <li>拒绝时抛 {@link RejectedExecutionException}，走全局异常处理返回可预期的
 *       过载失败路径。</li>
 * </ul>
 *
 * <p>分类：SHARED CONFIG — COORDINATION REQUIRED（MVC 异步为全局配置）。
 */
@Slf4j
@Configuration
public class MvcAsyncConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
    }

    @Bean(name = "mvcAsyncTaskExecutor")
    public ThreadPoolTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(rejectHandler());
        executor.initialize();
        return executor;
    }

    private RejectedExecutionHandler rejectHandler() {
        return (runnable, pool) -> {
            log.warn("MVC 异步任务被拒绝（线程池已满）: {}", runnable);
            throw new RejectedExecutionException("MVC 异步线程池已满，请求被拒绝");
        };
    }
}
