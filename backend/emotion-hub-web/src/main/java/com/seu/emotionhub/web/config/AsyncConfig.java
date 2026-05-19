package com.seu.emotionhub.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 *
 * @author EmotionHub Team
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

    private final AsyncExecutorProperties asyncExecutorProperties;

    /**
     * 异步任务执行器
     * 用于情感分析、通知发送等异步操作
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(asyncExecutorProperties.getCorePoolSize());
        executor.setMaxPoolSize(asyncExecutorProperties.getMaxPoolSize());
        executor.setQueueCapacity(asyncExecutorProperties.getQueueCapacity());
        executor.setThreadNamePrefix(asyncExecutorProperties.getThreadNamePrefix());
        executor.setKeepAliveSeconds(asyncExecutorProperties.getKeepAliveSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(asyncExecutorProperties.getKeepAliveSeconds());

        executor.initialize();

        log.info("异步任务执行器初始化完成: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());

        return executor;
    }
}
