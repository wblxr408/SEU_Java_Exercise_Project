package com.seu.emotionhub.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.seu.emotionhub.dao.mapper.PostMapper;
import com.seu.emotionhub.model.entity.Post;
import com.seu.emotionhub.model.enums.EmotionLabel;
import com.seu.emotionhub.model.enums.PostStatus;
import com.seu.emotionhub.service.EmotionAnalysisService;
import com.seu.emotionhub.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 情感分析服务实现类
 *
 * 当前版本：简化实现，使用规则引擎模拟情感分析
 * TODO: 后续可替换为真实的LLM API调用（OpenAI、通义千问等）
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "emotion.analysis.provider", havingValue = "keyword", matchIfMissing = true)
public class EmotionAnalysisServiceImpl implements EmotionAnalysisService {

    private final PostMapper postMapper;
    private final CacheService cacheService;
    private final Random random = new Random();

    // 积极词汇
    private static final String[] POSITIVE_WORDS = {
            "开心", "快乐", "幸福", "美好", "喜欢", "爱", "感恩", "棒", "好",
            "优秀", "成功", "满意", "高兴", "兴奋", "期待", "希望", "温暖"
    };

    // 消极词汇
    private static final String[] NEGATIVE_WORDS = {
            "难过", "伤心", "痛苦", "失望", "讨厌", "恨", "糟糕", "差",
            "失败", "烦恼", "焦虑", "压力", "孤独", "无助", "绝望", "冷漠"
    };

    @Override
    @Async("taskExecutor")
    public void analyzePostAsync(Long postId) {
        try {
            log.info("开始异步分析帖子情感: postId={}", postId);

            // 查询帖子
            Post post = postMapper.selectById(postId);
            if (post == null) {
                log.warn("帖子不存在: postId={}", postId);
                return;
            }

            // 模拟快速分析（关键词分析无需长时间等待）
            Thread.sleep(50 + random.nextInt(100)); // 50-150ms

            // 分析文本
            EmotionResult result = analyzeText(post.getContent());

            // 使用原子操作更新情感分析结果，避免并发死锁
            postMapper.updateEmotionAnalysis(
                post.getId(),
                result.getLabel(),
                BigDecimal.valueOf(result.getScore()),
                PostStatus.PUBLISHED.getCode()
            );
            // 清理帖子详情缓存，确保下次查询能获取到最新情感数据
            cacheService.delete(CacheService.CacheKey.POST_DETAIL + post.getId());
            invalidateStatsCache(post.getUserId());

            log.info("帖子情感分析完成: postId={}, label={}, score={}",
                    postId, result.getLabel(), result.getScore());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("情感分析被中断: postId={}", postId, e);
        } catch (Exception e) {
            log.error("情感分析失败: postId={}", postId, e);
        }
    }

    @Override
    public EmotionResult analyzeText(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new EmotionResult(0.0, EmotionLabel.NEUTRAL.getCode(),
                    "内容为空，无法分析", new String[0]);
        }

        // 统计积极词汇和消极词汇出现次数
        int positiveCount = 0;
        int negativeCount = 0;
        StringBuilder keywords = new StringBuilder();

        for (String word : POSITIVE_WORDS) {
            if (content.contains(word)) {
                positiveCount++;
                if (keywords.length() > 0)
                    keywords.append(",");
                keywords.append(word);
            }
        }

        for (String word : NEGATIVE_WORDS) {
            if (content.contains(word)) {
                negativeCount++;
                if (keywords.length() > 0)
                    keywords.append(",");
                keywords.append(word);
            }
        }

        // 计算情感分数（-1.0 到 1.0）
        double score;
        String label;
        String analysis;

        if (positiveCount == 0 && negativeCount == 0) {
            // 中性
            score = 0.0;
            label = EmotionLabel.NEUTRAL.getCode();
            analysis = "文本未检测到明显情感倾向，表达较为客观中立";
        } else {
            // 计算分数
            int total = positiveCount + negativeCount;
            score = (positiveCount - negativeCount) / (double) total;

            // 添加随机波动，使分数更自然
            score += (random.nextDouble() - 0.5) * 0.2;
            score = Math.max(-1.0, Math.min(1.0, score)); // 限制范围

            // 判断标签
            if (score > 0.2) {
                label = EmotionLabel.POSITIVE.getCode();
                analysis = String.format("文本表达出积极正面的情感，检测到%d个积极词汇", positiveCount);
            } else if (score < -0.2) {
                label = EmotionLabel.NEGATIVE.getCode();
                analysis = String.format("文本表达出消极负面的情感，检测到%d个消极词汇", negativeCount);
            } else {
                label = EmotionLabel.NEUTRAL.getCode();
                analysis = "文本情感较为中性，积极和消极因素基本平衡";
            }
        }

        String[] keywordArray = keywords.length() > 0
                ? keywords.toString().split(",")
                : new String[0];

        return new EmotionResult(score, label, analysis, keywordArray);
    }

    private void invalidateStatsCache(Long userId) {
        cacheService.delete(CacheService.CacheKey.STATS_USER + userId);
        cacheService.delete(CacheService.CacheKey.STATS_PLATFORM);
    }
}
