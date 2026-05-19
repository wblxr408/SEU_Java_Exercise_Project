package com.seu.emotionhub.web.controller;

import com.seu.emotionhub.common.result.Result;
import com.seu.emotionhub.model.dto.response.FeedResponse;
import com.seu.emotionhub.service.FeedService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Feed 流 Controller
 *
 * @author EmotionHub Team
 */
@Slf4j
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
@Tag(name = "Feed 流", description = "实时情感调节 Feed，支持 A/B 测试")
public class FeedController {

    private final FeedService feedService;

    /**
     * 获取个性化 Feed 流
     *
     * @param userId   用户ID
     * @param strategy 策略（emotional_adaptive / traditional），不传时由 A/B 测试自动分配
     * @param page     页码，从 0 开始
     * @param size     每页数量，默认 20，最大 50
     */
    @GetMapping
    @RateLimiter(name = "feed", fallbackMethod = "getFeedFallback")
    @Operation(summary = "获取个性化 Feed", description = "情感自适应策略与传统策略 A/B 测试，实时情感重排序")
    public Result<FeedResponse> getFeed(
            @Parameter(description = "用户ID", required = true)
            @RequestParam Long userId,

            @Parameter(description = "推荐策略: emotional_adaptive / traditional，不传则 A/B 自动分配")
            @RequestParam(required = false) String strategy,

            @Parameter(description = "页码，从0开始")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "每页数量，默认20，最大50")
            @RequestParam(defaultValue = "20") int size) {

        log.info("Feed 请求: userId={}, strategy={}, page={}, size={}", userId, strategy, page, size);
        FeedResponse response = feedService.generateFeed(userId, strategy, page, size);
        return Result.success(response);
    }

    /**
     * Feed 限流降级方法
     */
    public Result<FeedResponse> getFeedFallback(Long userId, String strategy, int page, int size, Throwable throwable) {
        log.warn("Feed 限流触发，快速失败: userId={}, error={}", userId, throwable.getMessage());
        return Result.error(429, "请求过于频繁，请稍后再试");
    }

    /**
     * 记录帖子点击（用于 A/B 测试 CTR 统计）
     *
     * @param logId 推荐日志ID（从 FeedResponse 中获取，当前版本通过 userId+postId 查询）
     */
    @PostMapping("/click/{logId}")
    @Operation(summary = "记录点击事件", description = "供前端上报点击，用于 A/B 测试 CTR 计算")
    public Result<Void> recordClick(
            @Parameter(description = "推荐日志ID")
            @PathVariable Long logId) {

        feedService.recordClick(logId);
        return Result.success();
    }
}
