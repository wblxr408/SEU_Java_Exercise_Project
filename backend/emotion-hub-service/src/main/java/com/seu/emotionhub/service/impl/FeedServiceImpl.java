package com.seu.emotionhub.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seu.emotionhub.dao.mapper.PostMapper;
import com.seu.emotionhub.dao.mapper.RecommendationLogMapper;
import com.seu.emotionhub.dao.mapper.UserInfluenceScoreMapper;
import com.seu.emotionhub.dao.mapper.UserMapper;
import com.seu.emotionhub.model.dto.response.EmotionStatsDTO;
import com.seu.emotionhub.model.dto.response.FeedResponse;
import com.seu.emotionhub.model.dto.response.PostVO;
import com.seu.emotionhub.model.entity.ContentEmotionTag;
import com.seu.emotionhub.model.entity.Post;
import com.seu.emotionhub.model.entity.RecommendationLog;
import com.seu.emotionhub.model.entity.User;
import com.seu.emotionhub.model.entity.UserInfluenceScore;
import com.seu.emotionhub.model.enums.EmotionStateEnum;
import com.seu.emotionhub.model.enums.PostStatus;
import com.seu.emotionhub.service.ABTestService;
import com.seu.emotionhub.service.ContentEmotionTagService;
import com.seu.emotionhub.service.FeedService;
import com.seu.emotionhub.service.RankerService;
import com.seu.emotionhub.service.UserEmotionService;
import com.seu.emotionhub.service.config.RankerProperties;

import com.seu.emotionhub.service.cache.FeedRedisKeyConstants;
import com.seu.emotionhub.service.cache.HotPostCacheService;
import com.seu.emotionhub.service.cache.RecommendationRedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Feed 流服务实现
 * <p>
 * 流水线：候选集合并 → 批量数据加载 → 情感重排序 → 分页 → 异步写日志
 * <p>
 * 排序公式（emotional_adaptive 策略）：
 * feedScore = 0.25 × qualityScore + 0.20 × recencyScore + 0.35 × emotionMatch + 0.20 × diversityScore
 * <p>
 * 与 recommendEmotional 的区别：候选来源更广（热门 + CF + 最新），情感权重更高（0.35 vs 0.30），带 Feed 缓存与 A/B 日志。
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final RecommendationLogMapper recommendationLogMapper;
    private final UserInfluenceScoreMapper userInfluenceScoreMapper;
    private final UserEmotionService userEmotionService;
    private final ContentEmotionTagService contentEmotionTagService;
    private final HotPostCacheService hotPostCacheService;
    private final ABTestService abTestService;
    private final RankerService rankerService;
    private final RankerProperties rankerProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int CANDIDATE_POOL_SIZE = 100;

    // -------------------------------------------------------
    // 公开接口
    // -------------------------------------------------------

    @Override
    public FeedResponse generateFeed(Long userId, String strategy, int page, int size) {
        try {
            log.info("Generating feed: userId={}, strategy={}, page={}, size={}", userId, strategy, page, size);
            size = normalizeSize(size);
            String resolvedStrategy = abTestService.resolveStrategy(userId, strategy);

            // 尝试命中 Feed 缓存（仅缓存第0页，翻页直接计算）
            if (page == 0) {
                FeedResponse cached = tryGetFromCache(userId, resolvedStrategy);
                if (cached != null) {
                    return cached;
                }
            }

            EmotionStateEnum state = userEmotionService.matchEmotionState(userId);
            log.info("User emotion state: {}", state);

            // 一次性获取用户情感上下文（2.1 产物），排序和日志均复用
            EmotionStatsDTO stats = userEmotionService.calculateSlidingWindowStats(userId, "24h");
            Double userAvgScore = stats != null ? stats.getAvgScore() : null;
            Double userVolatility = stats != null ? stats.getVolatility() : null;
            String trendType = userEmotionService.judgeEmotionTrend(userId, "24h");

            List<FeedCandidate> candidates = loadCandidates(userId);
            log.info("Loaded {} candidates", candidates.size());

            Map<Long, ContentEmotionTag> tagMap = contentEmotionTagService.getTagsByPostIds(
                    candidates.stream().map(c -> c.post.getId()).collect(Collectors.toSet())
            );
            log.info("Loaded {} emotion tags", tagMap.size());

        // 根据策略选择排序逻辑
        if ("emotional_adaptive".equals(resolvedStrategy)) {
            boolean mlUsed = false;
            if (rankerProperties.isEnabled()) {
                try {
                    scoreWithModel(candidates, tagMap, state, userAvgScore, userVolatility, trendType);
                    mlUsed = true;
                    log.info("ML ranker used: userId={}, candidates={}", userId, candidates.size()); // 加这行
                } catch (Exception e) {
                    log.warn("ML ranker failed, falling back to rule-based scoring: {}", e.getMessage());
                }
            }
            if (!mlUsed) {
                scoreEmotional(candidates, tagMap, state);
            }
        } else {
            scoreTraditional(candidates);
        }

        candidates.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        List<FeedCandidate> paged = paginate(candidates, page, size);

        // 批量加载用户信息（消除N+1查询）
        Set<Long> authorIds = paged.stream()
                .map(c -> c.post.getUserId())
                .collect(Collectors.toSet());
        Map<Long, User> userMap = batchLoadUsers(authorIds);

        List<PostVO> items = paged.stream()
                .map(c -> convertToPostVO(c.post, userMap.get(c.post.getUserId())))
                .collect(Collectors.toList());

        FeedResponse response = new FeedResponse();
        response.setUserId(userId);
        response.setStrategy(resolvedStrategy);
        response.setEmotionState(state.getName());
        response.setPage(page);
        response.setSize(size);
        response.setItems(items);

        if (page == 0) {
            putToCache(userId, resolvedStrategy, response);
        }

        // 异步写推荐日志，不阻塞响应
        asyncWriteLog(userId, resolvedStrategy, state.getName(),
                userAvgScore, userVolatility, trendType, paged);

        return response;
        } catch (Exception e) {
            log.error("Error generating feed for userId={}", userId, e);
            throw e;
        }
    }

    @Override
    public void recordClick(Long logId) {
        recommendationLogMapper.markClicked(logId);
    }

    // -------------------------------------------------------
    // 候选集加载
    // -------------------------------------------------------

    /**
     * 合并三路候选：CF推荐（优先）+ 热门 + 最新，去重后返回
     */
    private List<FeedCandidate> loadCandidates(Long userId) {
        Map<Long, Double> scoreMap = new LinkedHashMap<>();

        // 1. CF 推荐（已有离线预计算结果，得分最高权重）
        String recKey = String.format(RecommendationRedisKeyConstants.USER_RECOMMENDATIONS, userId);
        Set<ZSetOperations.TypedTuple<Object>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(recKey, 0, CANDIDATE_POOL_SIZE - 1);
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<Object> t : tuples) {
                if (t.getValue() != null) {
                    scoreMap.put(Long.parseLong(t.getValue().toString()),
                            t.getScore() == null ? 1.0 : t.getScore());
                }
            }
        }

        // 2. 热门内容（补充到目标池大小）
        if (scoreMap.size() < CANDIDATE_POOL_SIZE) {
            Set<Object> hotIds = hotPostCacheService.getHotPostIds(CANDIDATE_POOL_SIZE);
            if (hotIds != null) {
                for (Object id : hotIds) {
                    scoreMap.putIfAbsent(Long.parseLong(id.toString()), 0.8);
                }
            }
        }

        // 3. 最新帖子（兜底，保证 Feed 不为空）
        if (scoreMap.size() < CANDIDATE_POOL_SIZE / 3) {
            int remain = CANDIDATE_POOL_SIZE - scoreMap.size();
            List<Post> latest = postMapper.selectList(
                    new LambdaQueryWrapper<Post>()
                            .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                            .orderByDesc(Post::getCreatedAt)
                            .last("LIMIT " + remain)
            );
            for (Post p : latest) {
                scoreMap.putIfAbsent(p.getId(), 0.5);
            }
        }

        // 批量加载 Post 实体（过滤非发布状态）
        if (scoreMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<Post> posts = postMapper.selectBatchIds(scoreMap.keySet());
        List<FeedCandidate> candidates = new ArrayList<>();
        for (Post post : posts) {
            if (!PostStatus.PUBLISHED.getCode().equals(post.getStatus())) {
                continue;
            }
            // 排除用户自己的帖子
            if (userId.equals(post.getUserId())) {
                continue;
            }
            candidates.add(new FeedCandidate(post, scoreMap.getOrDefault(post.getId(), 0.5)));
        }
        return candidates;
    }

    // -------------------------------------------------------
    // 排序模型
    // -------------------------------------------------------

    /**
     * ML 模型排序（调用 Python 预测服务）
     * 失败时由调用方捕获异常并降级到 scoreEmotional
     */
    private void scoreWithModel(List<FeedCandidate> candidates,
                                 Map<Long, ContentEmotionTag> tagMap,
                                 EmotionStateEnum state,
                                 Double userAvgScore,
                                 Double userVolatility,
                                 String trendType) {
        // 2.3：作者影响力特征（批量查最新一条）
        Set<Long> authorIds = candidates.stream()
                .map(c -> c.post.getUserId())
                .collect(Collectors.toSet());
        Map<Long, Double> authorInfluenceScores = loadAuthorInfluenceScores(authorIds);

        // 把影响力写回候选对象，供日志记录使用
        for (FeedCandidate c : candidates) {
            c.authorInfluence = authorInfluenceScores.getOrDefault(c.post.getUserId(), 0.5);
        }

        List<Post> posts = candidates.stream().map(c -> c.post).collect(Collectors.toList());
        Map<Long, Double> baseScores = candidates.stream()
                .collect(Collectors.toMap(c -> c.post.getId(), c -> c.baseScore));

        List<Double> scores = rankerService.predict(
                posts, tagMap, state, baseScores,
                userAvgScore, userVolatility, trendType, authorInfluenceScores);

        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).finalScore = scores.get(i);
        }
    }

    /**
     * 批量加载作者影响力分并归一化到 0~1
     */
    private Map<Long, Double> loadAuthorInfluenceScores(Set<Long> authorIds) {
        Map<Long, Double> result = new HashMap<>();
        if (authorIds.isEmpty()) return result;

        List<UserInfluenceScore> records = userInfluenceScoreMapper.selectList(
                new LambdaQueryWrapper<UserInfluenceScore>()
                        .in(UserInfluenceScore::getUserId, authorIds)
                        .orderByDesc(UserInfluenceScore::getCalculationDate)
        );
        // 每个用户只取最新一条
        Map<Long, Double> raw = new LinkedHashMap<>();
        for (UserInfluenceScore r : records) {
            raw.putIfAbsent(r.getUserId(),
                    r.getInfluenceScore() != null ? r.getInfluenceScore().doubleValue() : 0.0);
        }
        if (raw.isEmpty()) return result;

        double maxScore = raw.values().stream().mapToDouble(v -> v).max().orElse(1.0);
        if (maxScore < 1e-6) maxScore = 1.0;
        for (Map.Entry<Long, Double> e : raw.entrySet()) {
            result.put(e.getKey(), e.getValue() / maxScore);
        }
        return result;
    }

    /**
     * 情感自适应排序
     * feedScore = 0.25×quality + 0.20×recency + 0.35×emotionMatch + 0.20×diversity
     */
    private void scoreEmotional(List<FeedCandidate> candidates,
                                 Map<Long, ContentEmotionTag> tagMap,
                                 EmotionStateEnum state) {
        if (candidates.isEmpty()) return;

        double maxBase = candidates.stream().mapToDouble(c -> c.baseScore).max().orElse(1.0);
        double minBase = candidates.stream().mapToDouble(c -> c.baseScore).min().orElse(0.0);

        // 预先计算作者出现频次用于多样性惩罚
        Map<Long, Long> authorFreq = candidates.stream()
                .collect(Collectors.groupingBy(c -> c.post.getUserId(), Collectors.counting()));
        long maxAuthorFreq = authorFreq.values().stream().mapToLong(v -> v).max().orElse(1);

        for (FeedCandidate c : candidates) {
            double quality  = normalize(c.baseScore, minBase, maxBase);
            double recency  = recencyScore(c.post.getCreatedAt(), 48.0);
            double emotion  = emotionMatch(c.post, tagMap.get(c.post.getId()), state);
            double diversity = 1.0 - (double) authorFreq.get(c.post.getUserId()) / maxAuthorFreq;

            c.finalScore = 0.25 * quality + 0.20 * recency + 0.35 * emotion + 0.20 * diversity;
        }
    }

    /**
     * 传统排序（对照组）：仅依赖热度分和时效性
     * feedScore = 0.7×quality + 0.3×recency
     */
    private void scoreTraditional(List<FeedCandidate> candidates) {
        if (candidates.isEmpty()) return;

        double maxBase = candidates.stream().mapToDouble(c -> c.baseScore).max().orElse(1.0);
        double minBase = candidates.stream().mapToDouble(c -> c.baseScore).min().orElse(0.0);

        for (FeedCandidate c : candidates) {
            double quality = normalize(c.baseScore, minBase, maxBase);
            double recency = recencyScore(c.post.getCreatedAt(), 72.0);
            c.finalScore = 0.7 * quality + 0.3 * recency;
        }
    }

    /**
     * 情感匹配度计算（复用并扩展 RecommendationServiceImpl 的逻辑）
     */
    private double emotionMatch(Post post, ContentEmotionTag tag, EmotionStateEnum state) {
        BigDecimal score = post.getEmotionScore();
        if (score == null) return 0.5;

        switch (state) {
            case LOW:
                // 情绪低落 → 推高正向内容
                return clamp((score.doubleValue() + 1) / 2);
            case ANXIOUS:
                // 焦虑 → 推平和中性内容（分数越接近0越好）
                return clamp(1 - Math.abs(score.doubleValue()));
            case HAPPY:
                // 情绪高涨 → 多样化，情感匹配权重降低，返回中等分
                return 0.5;
            case CALM:
                // 平静 → 偏向轻微正向
                return clamp(1 - Math.abs(score.doubleValue() - 0.2));
            case FLUCTUANT:
            default:
                if (tag != null && "NEUTRAL_CALM".equals(tag.getPrimaryTag())) {
                    return 0.8;
                }
                return clamp(1 - Math.abs(score.doubleValue()));
        }
    }

    // -------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------

    private int normalizeSize(int size) {
        if (size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }

    private List<FeedCandidate> paginate(List<FeedCandidate> sorted, int page, int size) {
        int from = page * size;
        if (from >= sorted.size()) return Collections.emptyList();
        int to = Math.min(from + size, sorted.size());
        return sorted.subList(from, to);
    }

    private double recencyScore(LocalDateTime createdAt, double halfLifeHours) {
        if (createdAt == null) return 0.5;
        long hours = Math.max(1, Duration.between(createdAt, LocalDateTime.now()).toHours());
        return Math.exp(-hours / halfLifeHours);
    }

    private double normalize(double value, double min, double max) {
        if (max - min < 1e-6) return 0.5;
        return (value - min) / (max - min);
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // -------------------------------------------------------
    // 缓存
    // -------------------------------------------------------

    private FeedResponse tryGetFromCache(Long userId, String strategy) {
        String key = String.format(FeedRedisKeyConstants.FEED_CACHE, userId, strategy, 0);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached == null) return null;
        try {
            return JSON.parseObject(cached.toString(), FeedResponse.class);
        } catch (Exception e) {
            log.warn("Feed 缓存反序列化失败, key={}", key, e);
            return null;
        }
    }

    private void putToCache(Long userId, String strategy, FeedResponse response) {
        String key = String.format(FeedRedisKeyConstants.FEED_CACHE, userId, strategy, 0);
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(response),
                    FeedRedisKeyConstants.FEED_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Feed 缓存写入失败, key={}", key, e);
        }
    }

    // -------------------------------------------------------
    // 异步日志
    // -------------------------------------------------------

    @Async
    void asyncWriteLog(Long userId, String strategy, String emotionState,
                       Double userAvgScore, Double userVolatility, String trendType,
                       List<FeedCandidate> paged) {
        if (paged == null || paged.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        List<RecommendationLog> batch = new ArrayList<>(paged.size());
        for (int i = 0; i < paged.size(); i++) {
            FeedCandidate c = paged.get(i);
            RecommendationLog record = new RecommendationLog();
            record.setUserId(userId);
            record.setPostId(c.post.getId());
            record.setStrategy(strategy);
            record.setEmotionState(emotionState);
            record.setScore(c.finalScore);
            record.setPosition(i + 1);
            record.setImpressedAt(now);
            record.setClicked(false);
            record.setUserAvgScore(userAvgScore);
            record.setUserVolatility(userVolatility);
            record.setTrendType(trendType);
            record.setAuthorInfluence(c.authorInfluence);
            batch.add(record);
        }
        try {
            recommendationLogMapper.insertBatchSomeColumn(batch);
        } catch (Exception ex) {
            log.warn("推荐日志批量写入失败，记录数={}: {}", batch.size(), ex.getMessage());
        }
    }

    // -------------------------------------------------------
    // PostVO 转换
    // -------------------------------------------------------

    private PostVO convertToPostVO(Post post) {
        User user = userMapper.selectById(post.getUserId());
        return convertToPostVO(post, user);
    }

    /**
     * 批量查询用户信息（消除N+1查询）
     */
    private Map<Long, User> batchLoadUsers(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        return users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /**
     * 转换为PostVO（使用已查询的用户信息）
     */
    private PostVO convertToPostVO(Post post, User user) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());
        vo.setContent(post.getContent());
        vo.setEmotionScore(post.getEmotionScore());
        vo.setEmotionLabel(post.getEmotionLabel());
        vo.setViewCount(post.getViewCount());
        vo.setLikeCount(post.getLikeCount());
        vo.setCommentCount(post.getCommentCount());
        vo.setStatus(post.getStatus());
        vo.setCreatedAt(post.getCreatedAt());
        vo.setUpdatedAt(post.getUpdatedAt());

        if (StringUtils.hasText(post.getImages())) {
            vo.setImages(JSON.parseArray(post.getImages(), String.class));
        }

        if (user != null) {
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        return vo;
    }

    // -------------------------------------------------------
    // 内部数据类
    // -------------------------------------------------------

    private static class FeedCandidate {
        final Post post;
        final double baseScore;
        double finalScore;
        double authorInfluence = 0.5;  // 作者影响力，供日志记录使用

        FeedCandidate(Post post, double baseScore) {
            this.post = post;
            this.baseScore = baseScore;
        }
    }
}
