package com.seu.emotionhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 测试专用 Redis 配置
 *
 * 提供不实际连接 Redis 服务器的 mock 组件：
 *   - MockRedisConnectionFactory（连接操作 no-op）
 *   - MockRedisTemplate（读写操作 no-op）
 *
 * 使用方式：在集成测试类上添加 @Import(TestRedisConfig.class)
 *
 * 注意：
 *   - 主 RedisConfig 同样加载，但 @Primary 确保本配置优先
 *   - TestcontainersIntegrationTest 不使用此配置（使用真实容器）
 */
@Configuration
public class TestRedisConfig {

    /**
     * Mock RedisConnectionFactory
     * 不尝试连接任何 Redis 服务器，所有连接操作 no-op
     */
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return new RedisConnectionFactory() {
            @Override
            public RedisConnection getConnection() {
                return new RedisConnection() {
                    // No-op: 所有操作不执行任何实际操作
                    @Override public Object execute(org.springframework.data.redis.core.RedisCallback<?> callback) { return null; }
                    @Override public void close() {}
                    @Override public boolean isQueueing() { return false; }
                    @Override public boolean isPipelined() { return false; }
                    @Override public void openPipeline() {}
                    @Override public java.util.List<Object> closePipeline() { return java.util.Collections.emptyList(); }
                    @Override public void setPipelined(boolean b) {}
                    @Override public org.springframework.data.redis.core.RedisAsyncCommands<Object, Object> async() { return null; }
                    @Override public org.springframework.data.redis.core.RedisCommands<Object, Object> commands() { return null; }
                    @Override public org.springframework.data.redis.core.StringRedisAdapter.StringRedisOperations stringCommands() { return null; }
                    @Override public boolean isSubscribed() { return false; }
                    @Override public org.springframework.data.redis.listener.PatternTopic getPatternTopic() { return null; }
                    @Override public org.springframework.data.redis.listener.ChannelTopic getChannelTopic() { return null; }
                    @Override public java.net.SocketAddress getLocalAddress() { return null; }
                    @Override public Long getDatabase() { return 0L; }
                    @Override public org.springframework.data.redis.serializer.RedisSerializer<?> getKeySerializer() { return null; }
                    @Override public org.springframework.data.redis.serializer.RedisSerializer<?> getValueSerializer() { return null; }
                    @Override public org.springframework.data.redis.serializer.RedisSerializer<?> getHashKeySerializer() { return null; }
                    @Override public org.springframework.data.redis.serializer.RedisSerializer<?> getHashValueSerializer() { return null; }
                    @Override public org.springframework.data.redis.serializer.RedisSerializer<?> getStringSerializer() { return null; }
                    @Override public boolean convertPipelineAndTxResults() { return false; }
                };
            }

            @Override
            public org.springframework.data.redis.connection.RedisClusterConnection getClusterConnection() {
                return null;
            }

            @Override
            public boolean getTimeout() { return false; }

            @Override
            public void destroy() {}
        };
    }

    /**
     * Mock RedisTemplate - 所有操作 no-op
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
