package com.seu.emotionhub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seu.emotionhub.common.enums.ErrorCode;
import com.seu.emotionhub.common.exception.BusinessException;
import com.seu.emotionhub.dao.mapper.CommentMapper;
import com.seu.emotionhub.dao.mapper.LikeRecordMapper;
import com.seu.emotionhub.dao.mapper.PostMapper;
import com.seu.emotionhub.dao.mapper.UserMapper;
import com.seu.emotionhub.model.entity.Comment;
import com.seu.emotionhub.model.entity.Post;
import com.seu.emotionhub.model.entity.User;
import com.seu.emotionhub.model.enums.EmotionLabel;
import com.seu.emotionhub.model.enums.PostStatus;
import com.seu.emotionhub.service.StatsService;
import com.seu.emotionhub.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 统计服务实现类
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final LikeRecordMapper likeRecordMapper;
    private final CacheService cacheService;

    @Override
    public Map<String, Object> getUserStats(Long userId) {
        String cacheKey = CacheService.CacheKey.STATS_USER + userId;
        Map<String, Object> cached = cacheService.get(cacheKey, Map.class);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 用户基本信息
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 并行查询所有统计（避免串行等待）
        CompletableFuture<Long> postCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode()))
        );

        CompletableFuture<Long> commentCountFuture = CompletableFuture.supplyAsync(() ->
            commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getUserId, userId)
                .eq(Comment::getDeleted, false))
        );

        CompletableFuture<Long> positiveCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getEmotionLabel, EmotionLabel.POSITIVE.getCode()))
        );

        CompletableFuture<Long> negativeCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getEmotionLabel, EmotionLabel.NEGATIVE.getCode()))
        );

        CompletableFuture<Long> neutralCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getEmotionLabel, EmotionLabel.NEUTRAL.getCode()))
        );

        // 查询帖子点赞总数
        CompletableFuture<List<Post>> userPostsFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .select(Post::getLikeCount))
        );

        // 等待所有查询完成
        CompletableFuture.allOf(postCountFuture, commentCountFuture, positiveCountFuture,
            negativeCountFuture, neutralCountFuture, userPostsFuture).join();

        long postCount = postCountFuture.join();
        long commentCount = commentCountFuture.join();
        long positiveCount = positiveCountFuture.join();
        long negativeCount = negativeCountFuture.join();
        long neutralCount = neutralCountFuture.join();
        List<Post> userPosts = userPostsFuture.join();
        int totalLikes = userPosts.stream().mapToInt(Post::getLikeCount).sum();

        Map<String, Object> stats = new HashMap<>();
        stats.put("userId", userId);
        stats.put("username", user.getUsername());
        stats.put("nickname", user.getNickname());
        stats.put("postCount", postCount);
        stats.put("commentCount", commentCount);
        stats.put("totalLikes", totalLikes);
        stats.put("emotionStats", Map.of(
                "positive", positiveCount,
                "negative", negativeCount,
                "neutral", neutralCount
        ));

        cacheService.set(cacheKey, stats, CacheService.CacheTTL.STATS, TimeUnit.SECONDS);
        return stats;
    }

    @Override
    public Map<String, Object> getPlatformStats() {
        String cacheKey = CacheService.CacheKey.STATS_PLATFORM;
        Map<String, Object> cached = cacheService.get(cacheKey, Map.class);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 并行查询所有统计
        CompletableFuture<Long> totalUsersFuture = CompletableFuture.supplyAsync(() ->
            userMapper.selectCount(null)
        );

        CompletableFuture<Long> totalPostsFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode()))
        );

        CompletableFuture<Long> totalCommentsFuture = CompletableFuture.supplyAsync(() ->
            commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getDeleted, false))
        );

        CompletableFuture<Long> totalLikesFuture = CompletableFuture.supplyAsync(() ->
            likeRecordMapper.selectCount(null)
        );

        CompletableFuture<Long> positiveCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getEmotionLabel, EmotionLabel.POSITIVE.getCode()))
        );

        CompletableFuture<Long> negativeCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getEmotionLabel, EmotionLabel.NEGATIVE.getCode()))
        );

        CompletableFuture<Long> neutralCountFuture = CompletableFuture.supplyAsync(() ->
            postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getEmotionLabel, EmotionLabel.NEUTRAL.getCode()))
        );

        // 等待所有查询完成
        CompletableFuture.allOf(totalUsersFuture, totalPostsFuture, totalCommentsFuture,
            totalLikesFuture, positiveCountFuture, negativeCountFuture, neutralCountFuture).join();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", totalUsersFuture.join());
        stats.put("totalPosts", totalPostsFuture.join());
        stats.put("totalComments", totalCommentsFuture.join());
        stats.put("totalLikes", totalLikesFuture.join());
        stats.put("emotionDistribution", Map.of(
                "positive", positiveCountFuture.join(),
                "negative", negativeCountFuture.join(),
                "neutral", neutralCountFuture.join()
        ));

        cacheService.set(cacheKey, stats, CacheService.CacheTTL.STATS, TimeUnit.SECONDS);
        return stats;
    }

    @Override
    public Map<String, Object> getMyStats() {
        Long userId = getCurrentUserId();
        return getUserStats(userId);
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Long) {
            return (Long) principal;
        }

        throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
}
