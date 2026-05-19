package com.seu.emotionhub.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.seu.emotionhub.dao.mapper.PostMapper;
import com.seu.emotionhub.model.entity.ApiKeyConfig;
import com.seu.emotionhub.model.entity.Post;
import com.seu.emotionhub.model.enums.EmotionLabel;
import com.seu.emotionhub.model.enums.PostStatus;
import com.seu.emotionhub.service.ApiKeyConfigService;
import com.seu.emotionhub.service.EmotionAnalysisService;
import com.seu.emotionhub.service.cache.CacheService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通义千问情感分析服务实现
 * 使用真实的LLM API进行情感分析
 * API Key优先级：用户个人配置 > 平台默认配置 > 配置文件
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service("qwenEmotionAnalysisService")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "emotion.analysis.provider", havingValue = "qwen")
public class QwenEmotionAnalysisServiceImpl implements EmotionAnalysisService {

    private final PostMapper postMapper;
    private final ApiKeyConfigService apiKeyConfigService;
    private final CacheService cacheService;

    @Value("${dashscope.api-key:}")
    private String fallbackApiKey;

    private static final String PROVIDER_QIANWEN = "qianwen";
    private static final String MODEL = "qwen-plus";

    private static final String SYSTEM_PROMPT = """
                                                            You are an expert sentiment analyzer for short social posts in Chinese and English.

            Task:
            - Infer the dominant sentiment from the full context (not isolated keywords).
            - Return one label from: POSITIVE, NEGATIVE, or NEUTRAL.
            - Return a score in [-1.0, 1.0], where:
                - -1.0 means extremely negative
                - 0.0 means neutral/mixed/uncertain
                - 1.0 means extremely positive

            Critical rules for Chinese colloquial text:
            - Distinguish complaint from playful teasing/joking tone. For example, phrases like “笑死/好笑/哈哈/绷不住” often indicate humor or a light mood, and should NOT be judged as strong NEGATIVE unless there is clear hostility.
            - For mixed sentiment with no clear dominance, choose NEUTRAL and keep score near 0.
            - If uncertain, prefer NEUTRAL over extreme polarity (NEGATIVE or POSITIVE).
            - Avoid labeling as extreme NEGATIVE or POSITIVE unless there is clear dominance of one sentiment.
            - If the sentiment is balanced or has conflicting emotions (e.g., both positive and negative signals are present), choose NEUTRAL and keep score near 0 unless one emotion clearly dominates.
            - For question or rhetorical sentences (e.g., "这也算好的吗？"), label as NEUTRAL or give a score close to 0, reflecting ambiguity or uncertainty.
            - If text has explicit sarcasm markers (e.g., “呵呵”, “白眼”, “真是谢谢你了”), prefer mild NEGATIVE rather than extreme NEGATIVE.
            - If text expresses relief/celebration mixed with fatigue (e.g., “终于结束了，累死我了”), prefer mild POSITIVE or NEUTRAL depending on dominance.

            Additional considerations:
            - Avoid overemphasizing extreme emotions for sentences that express mild, mixed, or casual sentiments.
            - For sarcasm or ambiguous humor, avoid labeling as extreme negative unless hostility is clear.
            - Consider the context—e.g., in professional settings, negative comments about performance might carry a different tone compared to casual, personal contexts.

            Few-shot guidance:
            Text: 真不行了，这个老师说话声音怎么这么好笑
            LABEL: NEUTRAL
            SCORE: 0.15

            Text: 这系统太垃圾了，气得我睡不着
            LABEL: NEGATIVE
            SCORE: -0.85

            Text: 今天答辩过了，老师夸我讲得很清楚，太开心了
            LABEL: POSITIVE
            SCORE: 0.88

            Text: 这也算好的吗？明明很差劲
            LABEL: NEUTRAL
            SCORE: 0.05

            Text: 好吧，今天终于过了，但还是有点担心
            LABEL: NEUTRAL
            SCORE: 0.2

            Text: 笑死我了，这作业要求也太离谱了吧
            LABEL: NEUTRAL
            SCORE: 0.1

            Text: 行行行，你最专业了（白眼）
            LABEL: NEGATIVE
            SCORE: -0.35

            Text: 终于下课了，谢天谢地，虽然脑子快炸了
            LABEL: POSITIVE
            SCORE: 0.35

            Text: 还行吧，不至于太差
            LABEL: NEUTRAL
            SCORE: -0.05

            Text: 这个功能真有你的，我直接无语
            LABEL: NEGATIVE
            SCORE: -0.55

            Text: 太绝了吧，居然一次就过了
            LABEL: POSITIVE
            SCORE: 0.72

            Text: 离谱中带点好笑，我都不知道该哭还是该笑
            LABEL: NEUTRAL
            SCORE: 0.0

            Text: 不愧是你，关键时刻还得靠你
            LABEL: POSITIVE
            SCORE: 0.42

            Text: 不愧是你，又把事情搞砸了
            LABEL: NEGATIVE
            SCORE: -0.62

            Text: 被夸了是挺开心，但我还是有点慌
            LABEL: NEUTRAL
            SCORE: 0.18

            Text: 呵呵，真是谢谢你了
            LABEL: NEGATIVE
            SCORE: -0.4

            Output format (strict, no extra text):
            LABEL: <POSITIVE|NEGATIVE|NEUTRAL>
            SCORE: <number>
                        """;

    @Override
    @CircuitBreaker(name = "emotion", fallbackMethod = "analyzePostAsyncFallback")
    @Async("taskExecutor")
    public void analyzePostAsync(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            log.error("帖子不存在: postId={}", postId);
            return;
        }

        log.info("开始分析帖子情感（通义千问）: postId={}", postId);

        // 从数据库按优先级获取API Key（用户配置 > 平台默认）
        String apiKey = resolveApiKey(post.getUserId());
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("未找到有效API Key，降级使用关键词分析: postId={}", postId);
            fallbackToKeywordAnalysis(post);
            return;
        }

        try {
            InternalEmotionResult result = analyzeWithQwen(apiKey, post.getContent());

            // 使用原子操作更新情感分析结果，避免并发死锁
            postMapper.updateEmotionAnalysis(
                post.getId(),
                result.label,
                java.math.BigDecimal.valueOf(result.score),
                PostStatus.PUBLISHED.getCode()
            );
            invalidateStatsCache(post.getUserId());
            invalidatePostDetailCache(post.getId());

            log.info("情感分析完成（通义千问）: postId={}, label={}, score={}",
                    postId, result.label, result.score);

        } catch (Exception e) {
            log.error("通义千问分析失败，降级使用关键词分析: postId={}", postId, e);
            fallbackToKeywordAnalysis(post);
        }
    }

    /**
     * 情感分析熔断降级方法
     * 当熔断器开启时，直接使用关键词分析
     */
    public void analyzePostAsyncFallback(Long postId, Throwable throwable) {
        log.warn("情感分析熔断触发，降级处理: postId={}, error={}", postId, throwable.getMessage());
        try {
            Post post = postMapper.selectById(postId);
            if (post != null) {
                fallbackToKeywordAnalysis(post);
            }
        } catch (Exception e) {
            log.error("熔断降级失败: postId={}", postId, e);
        }
    }

    @Override
    public EmotionResult analyzeText(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new EmotionResult(0.0, EmotionLabel.NEUTRAL.getCode(),
                    "内容为空，无法分析", new String[0]);
        }

        String apiKey = resolveApiKey(null);
        if (apiKey == null || apiKey.isEmpty()) {
            return fallbackAnalyzeText(content);
        }

        try {
            InternalEmotionResult result = analyzeWithQwen(apiKey, content);
            return new EmotionResult(
                    result.score,
                    result.label,
                    "Analyzed by Qwen AI",
                    new String[0]);
        } catch (Exception e) {
            log.error("通义千问文本分析失败", e);
            return fallbackAnalyzeText(content);
        }
    }

    /**
     * 按优先级解析API Key：用户个人配置 > 平台默认配置 > 配置文件
     */
    private String resolveApiKey(Long userId) {
        // 1. 先从数据库查（用户配置 > 平台默认）
        try {
            ApiKeyConfig config = apiKeyConfigService.getEffectiveApiKey(userId, PROVIDER_QIANWEN);
            if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                log.debug("使用数据库配置的API Key: userId={}, provider={}", userId, PROVIDER_QIANWEN);
                return config.getApiKey();
            }
        } catch (Exception e) {
            log.warn("从数据库获取API Key失败，使用配置文件兜底: {}", e.getMessage());
        }

        // 2. 配置文件兜底
        if (fallbackApiKey != null && !fallbackApiKey.isEmpty()) {
            log.debug("使用配置文件中的API Key");
            return fallbackApiKey;
        }

        return null;
    }

    /**
     * 使用通义千问进行情感分析
     */
    private InternalEmotionResult analyzeWithQwen(String apiKey, String content) throws Exception {
        Generation gen = new Generation();

        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(SYSTEM_PROMPT)
                .build();

        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content("Analyze sentiment for this text:\n" + content + "\nReturn only LABEL and SCORE.")
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(MODEL)
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .topP(0.8)
                .temperature(0.3f)
                .build();

        GenerationResult result = gen.call(param);
        String response = result.getOutput().getChoices().get(0).getMessage().getContent();

        InternalEmotionResult parsed = parseEmotionResult(response);
        return adjustForTeasingAndHumor(content, parsed);
    }

    /**
     * 解析通义千问的响应
     */
    private InternalEmotionResult parseEmotionResult(String response) {
        InternalEmotionResult result = new InternalEmotionResult();

        Pattern labelPattern = Pattern.compile("LABEL:\\s*(POSITIVE|NEGATIVE|NEUTRAL)", Pattern.CASE_INSENSITIVE);
        Matcher labelMatcher = labelPattern.matcher(response);
        if (labelMatcher.find()) {
            result.label = labelMatcher.group(1).toUpperCase();
        } else {
            result.label = EmotionLabel.NEUTRAL.getCode();
        }

        Pattern scorePattern = Pattern.compile("SCORE:\\s*(-?\\d+\\.?\\d*)");
        Matcher scoreMatcher = scorePattern.matcher(response);
        if (scoreMatcher.find()) {
            result.score = Double.parseDouble(scoreMatcher.group(1));
            result.score = Math.max(-1.0, Math.min(1.0, result.score));
        } else {
            result.score = 0.0;
        }

        log.debug("解析结果: label={}, score={}, rawResponse={}", result.label, result.score, response);
        return result;
    }

    /**
     * 轻量后处理：降低“调侃/玩笑语气”被判成明显负向的概率
     */
    private InternalEmotionResult adjustForTeasingAndHumor(String content, InternalEmotionResult rawResult) {
        if (content == null || content.isBlank()) {
            return rawResult;
        }

        boolean hasHumorCue = containsAny(content,
                "好笑", "哈哈", "笑死", "笑哭", "绷不住", "有意思", "好玩", "笑不活了");
        boolean hasTeasingCue = containsAny(content,
                "真不行了", "要笑死了", "这也太", "怎么这么");
        boolean hasStrongNegativeCue = containsAny(content,
                "垃圾", "恶心", "绝望", "崩溃", "讨厌", "气死", "痛苦", "抑郁", "恨", "烦死");

        if (EmotionLabel.NEGATIVE.getCode().equals(rawResult.label)
                && rawResult.score > -0.75
                && (hasHumorCue || hasTeasingCue)
                && !hasStrongNegativeCue) {
            rawResult.label = EmotionLabel.NEUTRAL.getCode();
            rawResult.score = Math.max(-0.1, rawResult.score + 0.45);
            log.info("检测到调侃/幽默语气，已将结果从偏负向校正为中性: score={}", rawResult.score);
        }

        return rawResult;
    }

    private boolean containsAny(String content, String... terms) {
        for (String term : terms) {
            if (content.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 降级方案：使用关键词分析
     */
    private void fallbackToKeywordAnalysis(Post post) {
        String content = post.getContent().toLowerCase();

        String[] positiveWords = { "happy", "joy", "love", "excellent", "wonderful", "great", "amazing",
                "开心", "快乐", "喜欢", "棒", "好", "美好", "幸福", "成功", "满足" };
        String[] negativeWords = { "sad", "angry", "hate", "terrible", "awful", "bad", "horrible",
                "难过", "生气", "讨厌", "糟糕", "差", "痛苦", "失望", "焦虑", "压力" };

        int positiveCount = 0;
        int negativeCount = 0;

        for (String word : positiveWords) {
            if (content.contains(word))
                positiveCount++;
        }

        for (String word : negativeWords) {
            if (content.contains(word))
                negativeCount++;
        }

        String label;
        double score;

        if (positiveCount > negativeCount) {
            label = EmotionLabel.POSITIVE.getCode();
            score = Math.min(0.9, 0.5 + positiveCount * 0.1);
        } else if (negativeCount > positiveCount) {
            label = EmotionLabel.NEGATIVE.getCode();
            score = Math.max(-0.9, -0.5 - negativeCount * 0.1);
        } else {
            label = EmotionLabel.NEUTRAL.getCode();
            score = 0.0;
        }

        // 使用原子操作更新情感分析结果，避免并发死锁
        postMapper.updateEmotionAnalysis(
            post.getId(),
            label,
            java.math.BigDecimal.valueOf(score),
            PostStatus.PUBLISHED.getCode()
        );
        invalidateStatsCache(post.getUserId());
        invalidatePostDetailCache(post.getId());

        log.info("情感分析完成（关键词降级）: postId={}, label={}, score={}",
                post.getId(), label, score);
    }

    /**
     * 文本分析的降级方案
     */
    private EmotionResult fallbackAnalyzeText(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new EmotionResult(0.0, EmotionLabel.NEUTRAL.getCode(),
                    "内容为空，无法分析", new String[0]);
        }

        String lowerContent = content.toLowerCase();

        String[] positiveWords = { "happy", "joy", "love", "excellent", "wonderful", "great", "amazing",
                "开心", "快乐", "喜欢", "棒", "好", "美好", "幸福", "成功", "满足" };
        String[] negativeWords = { "sad", "angry", "hate", "terrible", "awful", "bad", "horrible",
                "难过", "生气", "讨厌", "糟糕", "差", "痛苦", "失望", "焦虑", "压力" };

        int positiveCount = 0;
        int negativeCount = 0;

        for (String word : positiveWords) {
            if (lowerContent.contains(word))
                positiveCount++;
        }

        for (String word : negativeWords) {
            if (lowerContent.contains(word))
                negativeCount++;
        }

        String label;
        double score;

        if (positiveCount > negativeCount) {
            label = EmotionLabel.POSITIVE.getCode();
            score = Math.min(0.9, 0.5 + positiveCount * 0.1);
        } else if (negativeCount > positiveCount) {
            label = EmotionLabel.NEGATIVE.getCode();
            score = Math.max(-0.9, -0.5 - negativeCount * 0.1);
        } else {
            label = EmotionLabel.NEUTRAL.getCode();
            score = 0.0;
        }

        return new EmotionResult(score, label, "Analyzed by keyword matching (fallback)", new String[0]);
    }

    /**
     * 情感分析结果内部类
     */
    private static class InternalEmotionResult {
        String label = EmotionLabel.NEUTRAL.getCode();
        Double score = 0.0;
    }

    private void invalidateStatsCache(Long userId) {
        cacheService.delete(CacheService.CacheKey.STATS_USER + userId);
        cacheService.delete(CacheService.CacheKey.STATS_PLATFORM);
    }

    private void invalidatePostDetailCache(Long postId) {
        cacheService.delete(CacheService.CacheKey.POST_DETAIL + postId);
    }
}
