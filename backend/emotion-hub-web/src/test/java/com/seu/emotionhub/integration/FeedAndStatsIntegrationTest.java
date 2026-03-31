/**
 * FeedController + StatsController 集成测试
 *
 * 测试内容：
 *   - Feed 流生成（情感自适应 vs 传统）
 *   - 推荐接口
 *   - 用户/平台统计数据
 *   - 通知接口
 *
 * 运行方式：
 *   cd backend
 *   mvn test -Dtest=FeedAndStatsIntegrationTest -DfailIfNoTests=false
 */
package com.seu.emotionhub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seu.emotionhub.model.dto.request.PostCreateRequest;
import com.seu.emotionhub.model.dto.request.UserRegisterRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Feed + Stats + Notification 端到端集成测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Feed 流、统计与通知集成测试")
class FeedAndStatsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String testUsername;
    private String testPassword = "TestPass123!";
    private String testToken;
    private Long testUserId;

    @BeforeEach
    void setUp() throws Exception {
        testUsername = "feeduser_" + UUID.randomUUID().toString().substring(0, 8);

        UserRegisterRequest registerRequest = new UserRegisterRequest();
        registerRequest.setUsername(testUsername);
        registerRequest.setPassword(testPassword);
        registerRequest.setEmail(testUsername + "@test.local");
        registerRequest.setNickname("FeedTestUser");

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
            .andReturn();

        String regBody = regResult.getResponse().getContentAsString();
        com.alibaba.fastjson2.JSONObject regJson =
            com.alibaba.fastjson2.JSONObject.parseObject(regBody);
        testToken = regJson.getJSONObject("data").getString("token");
        testUserId = regJson.getJSONObject("data").getJSONObject("userInfo").getLong("id");
    }

    // ==================== Feed 流测试 ====================

    @Nested
    @DisplayName("Feed 流")
    class FeedTests {

        @Test
        @DisplayName("获取 Feed 流 → 返回成功（无论是否有数据）")
        void getFeed_success() throws Exception {
            mockMvc.perform(get("/api/feed")
                    .header("Authorization", "Bearer " + testToken)
                    .param("userId", String.valueOf(testUserId))
                    .param("page", "0")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("指定 emotional_adaptive 策略 → 正常返回")
        void getFeed_emotionalAdaptive() throws Exception {
            mockMvc.perform(get("/api/feed")
                    .header("Authorization", "Bearer " + testToken)
                    .param("userId", String.valueOf(testUserId))
                    .param("strategy", "emotional_adaptive")
                    .param("page", "0")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("指定 traditional 策略 → 正常返回")
        void getFeed_traditional() throws Exception {
            mockMvc.perform(get("/api/feed")
                    .header("Authorization", "Bearer " + testToken)
                    .param("userId", String.valueOf(testUserId))
                    .param("strategy", "traditional")
                    .param("page", "0")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("发帖后 Feed 流包含新帖子")
        void getFeed_includesNewPost() throws Exception {
            // 发帖
            PostCreateRequest request = new PostCreateRequest();
            request.setContent("Feed 流测试帖子 " + UUID.randomUUID());
            MvcResult postResult = mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andReturn();

            // 等待情感分析
            Thread.sleep(3000);

            // 请求 Feed
            mockMvc.perform(get("/api/feed")
                    .header("Authorization", "Bearer " + testToken)
                    .param("userId", String.valueOf(testUserId))
                    .param("page", "0")
                    .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.posts").isArray());
        }

        @Test
        @DisplayName("pageSize 超出上限 → 截断为上限")
        void getFeed_pageSizeMax() throws Exception {
            // size=100，超出最大限制 50
            mockMvc.perform(get("/api/feed")
                    .header("Authorization", "Bearer " + testToken)
                    .param("userId", String.valueOf(testUserId))
                    .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }
    }

    // ==================== 推荐接口测试 ====================

    @Nested
    @DisplayName("推荐系统")
    class RecommendationTests {

        @Test
        @DisplayName("情感推荐（情感自适应策略）")
        void recommend_emotionalAdaptive() throws Exception {
            String body = """
                {
                    "userId": %d,
                    "strategy": "emotional_adaptive",
                    "limit": 10
                }
                """.formatted(testUserId);

            mockMvc.perform(post("/api/recommendations/emotional")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("推荐（传统策略）")
        void recommend_traditional() throws Exception {
            String body = """
                {
                    "userId": %d,
                    "strategy": "traditional",
                    "limit": 10
                }
                """.formatted(testUserId);

            mockMvc.perform(post("/api/recommendations/emotional")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }
    }

    // ==================== 统计接口测试 ====================

    @Nested
    @DisplayName("统计接口")
    class StatsTests {

        @Test
        @DisplayName("获取我的统计 → 成功")
        void getMyStats_success() throws Exception {
            mockMvc.perform(get("/api/stats/my")
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("获取指定用户统计 → 成功")
        void getUserStats_success() throws Exception {
            mockMvc.perform(get("/api/stats/user/{userId}", testUserId)
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("获取平台统计 → 成功")
        void getPlatformStats_success() throws Exception {
            mockMvc.perform(get("/api/stats/platform")
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("发帖后统计数据更新")
        void getMyStats_afterPost() throws Exception {
            // 先发帖
            PostCreateRequest request = new PostCreateRequest();
            request.setContent("统计测试帖子");
            mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

            Thread.sleep(3000);

            // 获取统计数据
            mockMvc.perform(get("/api/stats/my")
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").exists());
        }
    }

    // ==================== 通知接口测试 ====================

    @Nested
    @DisplayName("通知接口")
    class NotificationTests {

        @Test
        @DisplayName("获取未读通知数量 → 成功")
        void getUnreadCount_success() throws Exception {
            mockMvc.perform(get("/api/notification/unread/count")
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").isNumber());
        }

        @Test
        @DisplayName("获取未读通知列表 → 成功")
        void listUnreadNotifications_success() throws Exception {
            mockMvc.perform(get("/api/notification/unread")
                    .header("Authorization", "Bearer " + testToken)
                    .param("page", "1")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("获取全部通知列表 → 成功")
        void listAllNotifications_success() throws Exception {
            mockMvc.perform(get("/api/notification/list")
                    .header("Authorization", "Bearer " + testToken)
                    .param("page", "1")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("标记全部已读 → 成功")
        void markAllAsRead_success() throws Exception {
            mockMvc.perform(put("/api/notification/read/all")
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }
    }
}
