package com.seu.emotionhub.service.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Redis缓存服务 - 增强版
 *
 * 技术亮点：
 * 1. 布隆过滤器防止缓存穿透
 * 2. 分布式锁防止缓存击穿
 * 3. 随机过期时间防止缓存雪崩
 * 4. 缓存预热机制
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 布隆过滤器 - 防止缓存穿透
     * 预期元素数量: 100万，误判率: 0.01
     */
    private BloomFilter<String> bloomFilter;

    @PostConstruct
    public void init() {
        // 初始化布隆过滤器
        bloomFilter = BloomFilter.create(
            Funnels.stringFunnel(StandardCharsets.UTF_8),
            1000000,  // 预期元素数量
            0.01      // 误判率
        );
        log.info("布隆过滤器初始化完成：容量=1000000, 误判率=0.01");
    }

    /**
     * 缓存key前缀
     */
    public static class CacheKey {
        public static final String POST_DETAIL = "post:detail:";
        public static final String POST_HOT = "post:hot";
        public static final String USER_INFO = "user:info:";
        public static final String STATS_USER = "stats:user:";
        public static final String STATS_PLATFORM = "stats:platform";
        public static final String EMOTION_TREND = "emotion:trend:";
        public static final String RATE_LIMIT = "rate:limit:";
        public static final String POST_LIST = "post:list:";
    }

    /**
     * 缓存过期时间（秒）
     */
    public static class CacheTTL {
        public static final long POST_DETAIL = 300; // 5分钟
        public static final long POST_LIST = 30; // 30秒
        public static final long POST_HOT = 600; // 10分钟
        public static final long USER_INFO = 1800; // 30分钟
        public static final long STATS = 3600; // 1小时
        public static final long EMOTION_TREND = 7200; // 2小时
    }

    /**
     * 设置缓存
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            log.error("设置缓存失败: key={}", key, e);
        }
    }

    /**
     * 获取缓存
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null && clazz.isInstance(value)) {
                return (T) value;
            }
        } catch (Exception e) {
            log.error("获取缓存失败: key={}", key, e);
        }
        return null;
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除缓存失败: key={}", key, e);
        }
    }

    /**
     * 批量删除缓存（模糊匹配）
     */
    public void deletePattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("批量删除缓存失败: pattern={}", pattern, e);
        }
    }

    /**
     * 判断key是否存在
     */
    public boolean exists(String key) {
        try {
            Boolean result = redisTemplate.hasKey(key);
            return result != null && result;
        } catch (Exception e) {
            log.error("判断缓存是否存在失败: key={}", key, e);
            return false;
        }
    }

    /**
     * 设置过期时间
     */
    public void expire(String key, long timeout, TimeUnit unit) {
        try {
            redisTemplate.expire(key, timeout, unit);
        } catch (Exception e) {
            log.error("设置过期时间失败: key={}", key, e);
        }
    }

    /**
     * 自增操作
     */
    public Long increment(String key) {
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.error("自增操作失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 自增指定值
     */
    public Long increment(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("自增操作失败: key={}, delta={}", key, delta, e);
            return null;
        }
    }

    // ==================== 高级缓存功能 ====================

    /**
     * 布隆过滤器：添加元素
     */
    public void bloomAdd(String value) {
        bloomFilter.put(value);
    }

    /**
     * 布隆过滤器：检查元素是否可能存在
     * @return true-可能存在，false-一定不存在
     */
    public boolean bloomMightContain(String value) {
        return bloomFilter.mightContain(value);
    }

    /**
     * 获取缓存（带回源函数）- 解决缓存穿透问题
     * 1. 先检查布隆过滤器
     * 2. 查询缓存
     * 3. 缓存未命中时，使用分布式锁防止击穿
     * 4. 回源查询数据库
     *
     * @param key 缓存key
     * @param clazz 返回类型
     * @param supplier 回源函数（查询数据库）
     * @param timeout 缓存过期时间
     * @param unit 时间单位
     * @return 缓存数据
     */
    public <T> T getWithFallback(String key, Class<T> clazz, Supplier<T> supplier, long timeout, TimeUnit unit) {
        // 1. 布隆过滤器检查（防止缓存穿透）
        if (!bloomMightContain(key)) {
            log.debug("布隆过滤器判断数据不存在: key={}", key);
            return null;
        }

        // 2. 查询缓存
        T value = get(key, clazz);
        if (value != null) {
            return value;
        }

        // 3. 使用分布式锁防止缓存击穿
        String lockKey = "lock:" + key;
        try {
            Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(lockAcquired)) {
                try {
                    // 再次检查缓存（双重检查）
                    value = get(key, clazz);
                    if (value != null) {
                        return value;
                    }

                    // 4. 回源查询数据库
                    value = supplier.get();

                    if (value != null) {
                        // 添加随机过期时间，防止缓存雪崩
                        long randomTimeout = timeout + (long) (Math.random() * 60);
                        set(key, value, randomTimeout, unit);
                        bloomAdd(key);
                    } else {
                        // 空值缓存，防止缓存穿透
                        set(key, "NULL", 60, TimeUnit.SECONDS);
                    }

                    return value;
                } finally {
                    // 释放锁
                    redisTemplate.delete(lockKey);
                }
            } else {
                // 未获取到锁，等待后重试
                Thread.sleep(50);
                return get(key, clazz);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取缓存时被中断: key={}", key, e);
            return supplier.get();
        } catch (Exception e) {
            log.error("获取缓存失败: key={}", key, e);
            return supplier.get();
        }
    }

    /**
     * 设置缓存（自动添加到布隆过滤器 + 随机过期时间）
     */
    public void setWithBloom(String key, Object value, long timeout, TimeUnit unit) {
        try {
            // 添加随机偏移，防止缓存雪崩
            long randomOffset = (long) (Math.random() * 60);
            redisTemplate.opsForValue().set(key, value, timeout + randomOffset, unit);
            bloomAdd(key);
        } catch (Exception e) {
            log.error("设置缓存失败: key={}", key, e);
        }
    }

    /**
     * 批量删除缓存（使用pipeline提高性能）
     */
    public void deleteBatch(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.error("批量删除缓存失败: keys={}", keys, e);
        }
    }

    /**
     * 获取热门内容（按分数排序）
     * 使用ZSet实现热门排行榜
     */
    public Set<Object> getHotItems(String key, int limit) {
        try {
            return redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        } catch (Exception e) {
            log.error("获取热门内容失败: key={}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * 增加热度分数
     */
    public void incrHotScore(String key, String member, double delta) {
        try {
            redisTemplate.opsForZSet().incrementScore(key, member, delta);
        } catch (Exception e) {
            log.error("增加热度分数失败: key={}, member={}", key, member, e);
        }
    }

    /**
     * 预热缓存
     * 在系统启动或低峰期预加载热门数据
     */
    public <T> void warmUp(String keyPrefix, List<T> dataList,
                           Function<T, String> keyExtractor,
                           long timeout, TimeUnit unit) {
        if (dataList == null || dataList.isEmpty()) {
            return;
        }

        log.info("开始预热缓存: keyPrefix={}, count={}", keyPrefix, dataList.size());

        try {
            for (T data : dataList) {
                String key = keyPrefix + keyExtractor.apply(data);
                setWithBloom(key, data, timeout, unit);
            }

            log.info("缓存预热完成: keyPrefix={}, count={}", keyPrefix, dataList.size());
        } catch (Exception e) {
            log.error("缓存预热失败: keyPrefix={}", keyPrefix, e);
        }
    }
}
