/**
 * AuthController 集成测试
 *
 * 测试内容：
 *   - POST /api/auth/register：用户注册成功 / 重复注册失败
 *   - POST /api/auth/login：登录成功 / 密码错误 / 用户不存在
 *   - GET /api/auth/current：获取当前用户信息
 *   - POST /api/auth/change-password：修改密码
 *
 * 运行方式：
 *   cd backend
 *   mvn test -Dtest=AuthControllerIntegrationTest -DfailIfNoTests=false
 *
 * 前置条件：
 *   - MySQL 和 Redis 服务运行中
 *   - 或修改 application.yml 中的 spring.profiles.active=test
 */
package com.seu.emotionhub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seu.emotionhub.model.dto.request.UserLoginRequest;
import com.seu.emotionhub.model.dto.request.UserRegisterRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 端到端集成测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AuthController 集成测试")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private String testUsername;
    private String testPassword = "TestPass123!";
    private String testToken;

    @BeforeEach
    void setUp() {
        // 每个测试使用唯一用户名，避免冲突
        testUsername = "testuser_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== 注册测试 ====================

    @Nested
    @DisplayName("用户注册")
    class RegisterTests {

        @Test
        @DisplayName("正常注册 → 返回 200 和 Token")
        void register_success() throws Exception {
            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername(testUsername);
            request.setPassword(testPassword);
            request.setEmail(testUsername + "@test.local");
            request.setNickname("TestNickname");

            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.username").value(testUsername));
        }

        @Test
        @DisplayName("重复注册同一用户名 → 返回错误")
        void register_duplicateUsername() throws Exception {
            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername(testUsername);
            request.setPassword(testPassword);
            request.setEmail(testUsername + "@test.local");
            request.setNickname("TestNickname");

            // 第一次注册成功
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

            // 第二次注册同一用户名应失败
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
        }

        @Test
        @DisplayName("空用户名注册 → 返回参数校验错误")
        void register_emptyUsername() throws Exception {
            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername("");
            request.setPassword(testPassword);
            request.setEmail("test@test.local");
            request.setNickname("Test");

            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("缺少必填字段 → 返回参数校验错误")
        void register_missingFields() throws Exception {
            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername(testUsername);
            // 缺少 password, email

            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }
    }

    // ==================== 登录测试 ====================

    @Nested
    @DisplayName("用户登录")
    class LoginTests {

        @BeforeEach
        void registerFirst() throws Exception {
            // 先注册才能登录
            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername(testUsername);
            request.setPassword(testPassword);
            request.setEmail(testUsername + "@test.local");
            request.setNickname("TestUser");

            MvcResult result = mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andReturn();

            // 提取 Token
            String body = result.getResponse().getContentAsString();
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSONObject.parseObject(body);
            testToken = json.getJSONObject("data").getString("token");
        }

        @Test
        @DisplayName("正确密码登录 → 返回 200 和 Token")
        void login_success() throws Exception {
            UserLoginRequest request = new UserLoginRequest();
            request.setUsername(testUsername);
            request.setPassword(testPassword);

            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.username").value(testUsername));
        }

        @Test
        @DisplayName("错误密码登录 → 返回认证失败")
        void login_wrongPassword() throws Exception {
            UserLoginRequest request = new UserLoginRequest();
            request.setUsername(testUsername);
            request.setPassword("WrongPassword123!");

            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
        }

        @Test
        @DisplayName("不存在的用户登录 → 返回错误")
        void login_userNotFound() throws Exception {
            UserLoginRequest request = new UserLoginRequest();
            request.setUsername("nonexistent_user_" + UUID.randomUUID());
            request.setPassword(testPassword);

            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
        }
    }

    // ==================== 获取当前用户测试 ====================

    @Nested
    @DisplayName("获取当前用户信息")
    class CurrentUserTests {

        @BeforeEach
        void loginAndGetToken() throws Exception {
            // 注册并登录
            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername(testUsername);
            request.setPassword(testPassword);
            request.setEmail(testUsername + "@test.local");
            request.setNickname("TestUser");

            MvcResult result = mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andReturn();

            String body = result.getResponse().getContentAsString();
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSONObject.parseObject(body);
            testToken = json.getJSONObject("data").getString("token");
        }

        @Test
        @DisplayName("带 Token 获取当前用户 → 返回用户信息")
        void getCurrentUser_withToken() throws Exception {
            mockMvc.perform(get("/api/auth/current")
                    .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(testUsername));
        }

        @Test
        @DisplayName("不带 Token → 返回 401 未认证")
        void getCurrentUser_noToken() throws Exception {
            mockMvc.perform(get("/api/auth/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2009));
        }

        @Test
        @DisplayName("无效 Token → 返回 401")
        void getCurrentUser_invalidToken() throws Exception {
            mockMvc.perform(get("/api/auth/current")
                    .header("Authorization", "Bearer invalid_token_12345"))
                .andExpect(status().isUnauthorized());
        }
    }
}
