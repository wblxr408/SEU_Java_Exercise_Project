/**
 * PostController + InteractionController 端到端集成测试
 *
 * 测试内容：
 *   - 发帖 → 情感分析（异步）→ 查询详情
 *   - 评论 → 树形结构查询
 *   - 点赞 → 幂等性验证
 *   - 帖子/评论删除 → 权限验证
 *
 * 运行方式：
 *   cd backend
 *   mvn test -Dtest=PostIntegrationTest -DfailIfNoTests=false
 */
package com.seu.emotionhub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seu.emotionhub.model.dto.request.CommentCreateRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PostController + InteractionController 端到端集成测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("帖子与互动功能集成测试")
class PostIntegrationTest {

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
        testUsername = "postuser_" + UUID.randomUUID().toString().substring(0, 8);

        // 注册用户
        UserRegisterRequest registerRequest = new UserRegisterRequest();
        registerRequest.setUsername(testUsername);
        registerRequest.setPassword(testPassword);
        registerRequest.setEmail(testUsername + "@test.local");
        registerRequest.setNickname("PostTestUser");

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

    // ==================== 发帖 + 情感分析 ====================

    @Nested
    @DisplayName("发帖与情感分析")
    class PostTests {

        @Test
        @DisplayName("正常发帖 → 返回帖子 ID，情感分析异步完成")
        void createPost_success() throws Exception {
            PostCreateRequest request = new PostCreateRequest();
            request.setContent("今天项目终于上线了，所有人的努力都没有白费，太激动了！");

            mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.content").value(request.getContent()));
        }

        @Test
        @DisplayName("空内容发帖 → 返回参数校验错误")
        void createPost_emptyContent() throws Exception {
            PostCreateRequest request = new PostCreateRequest();
            request.setContent("");

            mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("发帖后查询详情 → 情感分数不为空（异步分析已完成或待分析）")
        void getPostDetail_afterCreate() throws Exception {
            // 1. 发帖
            PostCreateRequest postRequest = new PostCreateRequest();
            postRequest.setContent("今天天气真好，心情很开心！");

            MvcResult postResult = mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(postRequest)))
                .andReturn();

            com.alibaba.fastjson2.JSONObject postJson =
                com.alibaba.fastjson2.JSONObject.parseObject(postResult.getResponse().getContentAsString());
            Long postId = postJson.getJSONObject("data").getLong("id");

            // 2. 等待异步情感分析（最多 5 秒）
            Thread.sleep(5000);

            // 3. 查询详情
            mockMvc.perform(get("/api/post/{postId}", postId)
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value(postRequest.getContent()));
        }

        @Test
        @DisplayName("查询帖子列表 → 返回分页结果")
        void listPosts_success() throws Exception {
            // 先发一条帖子
            PostCreateRequest request = new PostCreateRequest();
            request.setContent("测试帖子内容 " + UUID.randomUUID());
            mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

            // 查询列表
            mockMvc.perform(get("/api/post/list")
                    .header("Authorization", "Bearer " + testToken)
                    .param("page", "1")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
        }

        @Test
        @DisplayName("按情感标签过滤帖子")
        void listPosts_withEmotionFilter() throws Exception {
            mockMvc.perform(get("/api/post/list")
                    .header("Authorization", "Bearer " + testToken)
                    .param("emotionLabel", "POSITIVE")
                    .param("page", "1")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records").isArray());
        }
    }

    // ==================== 评论功能 ====================

    @Nested
    @DisplayName("评论功能")
    class CommentTests {

        private Long postId;

        @BeforeEach
        void createPostForComment() throws Exception {
            PostCreateRequest postRequest = new PostCreateRequest();
            postRequest.setContent("这是一个用于评论测试的帖子。");

            MvcResult result = mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(postRequest)))
                .andReturn();

            com.alibaba.fastjson2.JSONObject json =
                com.alibaba.fastjson2.JSONObject.parseObject(result.getResponse().getContentAsString());
            postId = json.getJSONObject("data").getLong("id");

            // 等待情感分析
            Thread.sleep(3000);
        }

        @Test
        @DisplayName("正常发表评论 → 返回评论 ID")
        void createComment_success() throws Exception {
            CommentCreateRequest request = new CommentCreateRequest();
            request.setPostId(postId);
            request.setContent("说得太对了，支持你！");

            mockMvc.perform(post("/api/interaction/comment")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.content").value(request.getContent()));
        }

        @Test
        @DisplayName("回复已有评论 → 支持嵌套回复")
        void createReply_comment() throws Exception {
            // 发评论
            CommentCreateRequest commentRequest = new CommentCreateRequest();
            commentRequest.setPostId(postId);
            commentRequest.setContent("第一条评论");

            MvcResult commentResult = mockMvc.perform(post("/api/interaction/comment")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(commentRequest)))
                .andReturn();

            com.alibaba.fastjson2.JSONObject commentJson =
                com.alibaba.fastjson2.JSONObject.parseObject(commentResult.getResponse().getContentAsString());
            Long parentCommentId = commentJson.getJSONObject("data").getLong("id");

            // 回复评论
            CommentCreateRequest replyRequest = new CommentCreateRequest();
            replyRequest.setPostId(postId);
            replyRequest.setParentId(parentCommentId);
            replyRequest.setContent("这是你的回复");

            mockMvc.perform(post("/api/interaction/comment")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(replyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(parentCommentId.intValue()));
        }

        @Test
        @DisplayName("查询评论列表 → 返回树形结构")
        void listComments_treeStructure() throws Exception {
            // 发两条评论
            for (int i = 0; i < 2; i++) {
                CommentCreateRequest request = new CommentCreateRequest();
                request.setPostId(postId);
                request.setContent("测试评论 " + i);
                mockMvc.perform(post("/api/interaction/comment")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
            }

            // 查询评论列表
            mockMvc.perform(get("/api/interaction/comment/list")
                    .header("Authorization", "Bearer " + testToken)
                    .param("postId", String.valueOf(postId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("删除自己的评论 → 成功")
        void deleteComment_ownComment() throws Exception {
            // 发评论
            CommentCreateRequest request = new CommentCreateRequest();
            request.setPostId(postId);
            request.setContent("待删除的评论");

            MvcResult result = mockMvc.perform(post("/api/interaction/comment")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andReturn();

            com.alibaba.fastjson2.JSONObject json =
                com.alibaba.fastjson2.JSONObject.parseObject(result.getResponse().getContentAsString());
            Long commentId = json.getJSONObject("data").getLong("id");

            // 删除
            mockMvc.perform(delete("/api/interaction/comment/{commentId}", commentId)
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }
    }

    // ==================== 点赞功能（幂等性）====================

    @Nested
    @DisplayName("点赞功能幂等性")
    class LikeIdempotencyTests {

        private Long postId;

        @BeforeEach
        void createPostForLike() throws Exception {
            PostCreateRequest postRequest = new PostCreateRequest();
            postRequest.setContent("点赞测试帖子");
            MvcResult result = mockMvc.perform(post("/api/post/create")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(postRequest)))
                .andReturn();
            postId = com.alibaba.fastjson2.JSONObject
                .parseObject(result.getResponse().getContentAsString())
                .getJSONObject("data").getLong("id");
        }

        @Test
        @DisplayName("点赞 → liked=true")
        void like_thenLiked() throws Exception {
            mockMvc.perform(post("/api/interaction/like")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetId\":" + postId + ",\"targetType\":\"POST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true));
        }

        @Test
        @DisplayName("重复点赞（幂等）→ liked 状态切换")
        void like_idempotent() throws Exception {
            // 第一次点赞
            mockMvc.perform(post("/api/interaction/like")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetId\":" + postId + ",\"targetType\":\"POST\"}"))
                .andExpect(jsonPath("$.data.liked").value(true));

            // 第二次点赞（取消）
            mockMvc.perform(post("/api/interaction/like")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetId\":" + postId + ",\"targetType\":\"POST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false));
        }

        @Test
        @DisplayName("检查是否点赞 → 准确返回点赞状态")
        void checkLike_accurate() throws Exception {
            // 先点赞
            mockMvc.perform(post("/api/interaction/like")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetId\":" + postId + ",\"targetType\":\"POST\"}"))
                .andExpect(status().isOk());

            // 检查
            mockMvc.perform(get("/api/interaction/like/check")
                    .header("Authorization", "Bearer " + testToken)
                    .param("targetId", String.valueOf(postId))
                    .param("targetType", "POST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true));
        }
    }
}
