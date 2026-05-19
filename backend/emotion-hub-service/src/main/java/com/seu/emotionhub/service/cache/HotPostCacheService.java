package com.seu.emotionhub.service.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seu.emotionhub.dao.mapper.PostMapper;
import com.seu.emotionhub.model.entity.Post;
import com.seu.emotionhub.model.enums.PostStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 热门帖子缓存服务
 *
 * 功能：
 * 1. 实时更新热门帖子排行榜
 * 2. 定时刷新热门帖子缓存
 * 3. 缓存预热（系统启动时）
 * 4. 防止缓存击穿、穿透、雪崩
 *
 * 技术亮点：
 * - 使用 Redis ZSet 实现热门排行榜
 * - 布隆过滤器防止缓存穿透
 * - 分布式锁防止缓存击穿
 * - 随机过期时间防止缓存雪崩
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotPostCacheService {

    private final CacheService cacheService;
    private final PostMapper postMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 热门帖子排行榜key
     */
    private static final String HOT_POST_RANKING = "post:hot:ranking";

    /**
     * 热门帖子详情缓存前缀
     */
    private static final String HOT_POST_DETAIL = CacheService.CacheKey.POST_DETAIL;

    /**
     * 热度分数计算权重
     */
    private static final double VIEW_WEIGHT = 1.0;
    private static final double LIKE_WEIGHT = 3.0;
    private static final double COMMENT_WEIGHT = 5.0;

    /**
     * 系统启动时预热缓存
     */
    @PostConstruct
    public void init() {
        log.info("开始预热热门帖子缓存...");
        refreshHotPosts();
        log.info("热门帖子缓存预热完成");
    }

    /**
     * 定时刷新热门帖子（每10分钟）
     */
    @Scheduled(fixedRate = 600000) // 10分钟
    public void refreshHotPosts() {
        try {
            // 查询热门帖子（按综合热度排序）
            List<Post> hotPosts = postMapper.selectList(
                new LambdaQueryWrapper<Post>()
                    .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                    .orderByDesc(Post::getViewCount)
                    .orderByDesc(Post::getLikeCount)
                    .orderByDesc(Post::getCommentCount)
                    .last("LIMIT 100")
            );

            // 更新热门排行榜（重建，避免分数被重复累加）
            redisTemplate.delete(HOT_POST_RANKING);
            hotPosts.forEach(post -> {
                double score = calculateHotScore(post);
                redisTemplate.opsForZSet().add(HOT_POST_RANKING, String.valueOf(post.getId()), score);
            });

            // 预热热门帖子详情
            cacheService.warmUp(
                HOT_POST_DETAIL,
                hotPosts,
                post -> String.valueOf(post.getId()),
                10,
                TimeUnit.MINUTES
            );

            log.info("热门帖子缓存刷新完成，共 {} 条", hotPosts.size());
        } catch (Exception e) {
            log.error("刷新热门帖子缓存失败", e);
        }
    }

    /**
     * 获取热门帖子列表
     *
     * @param limit 数量限制
     * @return 热门帖子ID列表
     */
    public Set<Object> getHotPostIds(int limit) {
        return cacheService.getHotItems(HOT_POST_RANKING, limit);
    }

    /**
     * 获取帖子详情（带缓存）
     *
     * @param postId 帖子ID
     * @return 帖子详情
     */
    public Post getPostWithCache(Long postId) {
        String key = HOT_POST_DETAIL + postId;

        return cacheService.getWithFallback(
            key,
            Post.class,
            () -> postMapper.selectById(postId),
            5,
            TimeUnit.MINUTES
        );
    }

    /**
     * 更新帖子热度（用户浏览/点赞/评论时调用）
     *
     * @param postId 帖子ID
     * @param action 操作类型：view, like, comment
     */
    public void updateHotScore(Long postId, String action) {
        try {
            Post post = postMapper.selectById(postId);
            if (post == null || !PostStatus.PUBLISHED.getCode().equals(post.getStatus())) {
                return;
            }

            // 计算增量分数
            double deltaScore = switch (action) {
                case "view" -> VIEW_WEIGHT;
                case "like" -> LIKE_WEIGHT;
                case "comment" -> COMMENT_WEIGHT;
                default -> 0.0;
            };

            // 更新热度排行榜
            cacheService.incrHotScore(HOT_POST_RANKING, String.valueOf(postId), deltaScore);

            // 如果是热门帖子，刷新缓存
            Set<Object> hotPostIds = getHotPostIds(100);
            if (hotPostIds.stream().map(Object::toString).collect(Collectors.toSet()).contains(String.valueOf(postId))) {
                String key = HOT_POST_DETAIL + postId;
                cacheService.delete(key);
            }

            // 清理列表缓存（热度变化会影响排序）
            cacheService.deletePattern(CacheService.CacheKey.POST_LIST + "*");

            log.debug("更新帖子热度: postId={}, action={}, delta={}", postId, action, deltaScore);
        } catch (Exception e) {
            log.error("更新帖子热度失败: postId={}, action={}", postId, action, e);
        }
    }

    /**
     * 计算帖子热度分数
     * 公式：热度 = 浏览数 * 1 + 点赞数 * 3 + 评论数 * 5
     */
    private double calculateHotScore(Post post) {
        return post.getViewCount() * VIEW_WEIGHT
            + post.getLikeCount() * LIKE_WEIGHT
            + post.getCommentCount() * COMMENT_WEIGHT;
    }

    /**
     * 清除帖子缓存（帖子被删除时调用）
     */
    public void invalidatePostCache(Long postId) {
        try {
            String key = HOT_POST_DETAIL + postId;
            cacheService.delete(key);

            // 从热门排行榜移除
            redisTemplate.opsForZSet().remove(HOT_POST_RANKING, String.valueOf(postId));

            log.debug("清除帖子缓存: postId={}", postId);
        } catch (Exception e) {
            log.error("清除帖子缓存失败: postId={}", postId, e);
        }
    }

    /**
     * 批量清除帖子缓存
     */
    public void invalidatePostCacheBatch(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return;
        }

        try {
            Set<String> keys = postIds.stream()
                .map(id -> HOT_POST_DETAIL + id)
                .collect(Collectors.toSet());

            cacheService.deleteBatch(keys);

            log.debug("批量清除帖子缓存: count={}", postIds.size());
        } catch (Exception e) {
            log.error("批量清除帖子缓存失败", e);
        }
    }
}
