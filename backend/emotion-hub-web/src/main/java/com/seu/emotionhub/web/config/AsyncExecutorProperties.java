package com.seu.emotionhub.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步线程池配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "async.executor")
public class AsyncExecutorProperties {
    private int corePoolSize = 20;
    private int maxPoolSize = 100;
    private int queueCapacity = 500;
    private String threadNamePrefix = "emotion-async-";
    private int keepAliveSeconds = 60;
}
