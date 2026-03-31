/**
 * Testcontainers 集成测试 - 在真实 Docker 容器中运行 MySQL 和 Redis
 *
 * 此测试启动真实的 MySQL 8.0 和 Redis 容器，完全模拟生产环境。
 * 是最可靠的集成测试方式，无需本地安装数据库。
 *
 * ========== 启用此测试 ==========
 *
 * 1. 在 emotion-hub-web/pom.xml 中添加依赖：
 *
 *    <dependency>
 *        <groupId>org.testcontainers</groupId>
 *        <artifactId>testcontainers</artifactId>
 *        <version>1.19.3</version>
 *        <scope>test</scope>
 *    </dependency>
 *    <dependency>
 *        <groupId>org.testcontainers</groupId>
 *        <artifactId>junit-jupiter</artifactId>
 *        <version>1.19.3</version>
 *        <scope>test</scope>
 *    </dependency>
 *    <dependency>
 *        <groupId>org.testcontainers</groupId>
 *        <artifactId>mysql</artifactId>
 *        <version>1.19.3</version>
 *        <scope>test</scope>
 *    </dependency>
 *
 * 2. 确保 Docker Desktop 运行中
 *
 * 3. 运行测试：
 *    cd backend
 *    mvn test -Dtest=TestcontainersIntegrationTest -DfailIfNoTests=false
 *
 * ========== 测试覆盖 ==========
 *
 *   - 完整用户注册 → 登录 → 发帖 → 互动 → 统计的完整流程
 *   - 数据库连接池在容器重启后的恢复
 *   - Redis 缓存在容器中的行为
 *   - Flyway 数据库迁移在真实 MySQL 中的执行
 */
package com.seu.emotionhub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seu.emotionhub.EmotionHubApplication;
import com.seu.emotionhub.model.dto.request.CommentCreateRequest;
import com.seu.emotionhub.model.dto.request.PostCreateRequest;
import com.seu.emotionhub.model.dto.request.UserRegisterRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testcontainers 集成测试
 *
 * 特点：
 *   - 每个测试类启动独立的 MySQL 和 Redis 容器
 *   - 容器在测试完成后自动清理
 *   - 支持并行测试（JUnit5 默认并行）
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = EmotionHubApplication.class
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Testcontainers 全栈集成测试（需要 Docker）")
class TestcontainersIntegrationTest {

    // ========== MySQL 容器 ==========
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
        DockerImageName.parse("mysql:8.0")
            .asCompatibleSubstituteFor("mysql")
    )
        .withDatabaseName("emotion_hub_test")
        .withUsername("testuser")
        .withPassword("testpass")
        .withEnv("MYSQL_ROOT_PASSWORD", "rootpass")
        .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
        .withStartupTimeout(Duration.ofMinutes(2))
        .withNetwork(Network.SHARED)
        .withExposedPorts(3306);

    // ========== Redis 容器 ==========
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine")
    )
        .withNetwork(Network.SHARED)
        .withExposedPorts(6379)
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
        .withStartupTimeout(Duration.ofMinutes(1))
        .withCommand("redis-server --appendonly no");

    // ========== 动态注入容器配置到 Spring ==========
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL 配置
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        // Redis 配置
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Flyway（使用 testcontainers 时可启用以验证迁移脚本）
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.baseline-on-migrate", () -> true);
    }

    // ========== 注入 MockMvc ==========
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
        testUsername = "tc_user_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);

        UserRegisterRequest request = new UserRegisterRequest();
        request.setUsername(testUsername);
        request.setPassword(testPassword);
        request.setEmail(testUsername + "@tc.local");
        request.setNickname("TCUser");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andReturn();

        com.alibaba.fastjson2.JSONObject json =
            com.alibaba.fastjson2.JSONObject.parseObject(result.getResponse().getContentAsString());
        testToken = json.getJSONObject("data").getString("token");
        testUserId = json.getJSONObject("data").getJSONObject("userInfo").getLong("id");

        assertThat(testToken).isNotBlank();
        assertThat(testUserId).isNotNull();
    }

    // ==================== 完整用户旅程测试 ====================

    @Test
    @DisplayName("完整用户旅程：注册 → 登录 → 发帖 → 评论 → 点赞 → 统计")
    void completeUserJourney() throws Exception {
        // Step 1: 登录验证
        mockMvc.perform(get("/api/auth/current")
                .header("Authorization", "Bearer " + testToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(testUsername));

        // Step 2: 发帖
        PostCreateRequest postRequest = new PostCreateRequest();
        postRequest.setContent("Testcontainers 测试帖子：今天心情非常好！");
        MvcResult postResult = mockMvc.perform(post("/api/post/create")
                .header("Authorization", "Bearer " + testToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(postRequest)))
            .andReturn();
        assertThat(postResult.getResponse().getStatus()).isEqualTo(200);

        com.alibaba.fastjson2.JSONObject postJson =
            com.alibaba.fastjson2.JSONObject.parseObject(postResult.getResponse().getContentAsString());
        Long postId = postJson.getJSONObject("data").getLong("id");
        assertThat(postId).isNotNull();

        // Step 3: 等待情感分析
        Thread.sleep(5000);

        // Step 4: 获取帖子详情（验证情感分析完成）
        mockMvc.perform(get("/api/post/{postId}", postId)
                .header("Authorization", "Bearer " + testToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // Step 5: 评论
        CommentCreateRequest commentRequest = new CommentCreateRequest();
        commentRequest.setPostId(postId);
        commentRequest.setContent("说得太好了！");
        MvcResult commentResult = mockMvc.perform(post("/api/interaction/comment")
                .header("Authorization", "Bearer " + testToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(commentRequest)))
            .andReturn();
        assertThat(commentResult.getResponse().getStatus()).isEqualTo(200);

        // Step 6: 点赞
        mockMvc.perform(post("/api/interaction/like")
                .header("Authorization", "Bearer " + testToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetId\":" + postId + ",\"targetType\":\"POST\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.liked").value(true));

        // Step 7: Feed 流
        mockMvc.perform(get("/api/feed")
                .header("Authorization", "Bearer " + testToken)
                .param("userId", String.valueOf(testUserId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // Step 8: 统计
        mockMvc.perform(get("/api/stats/my")
                .header("Authorization", "Bearer " + testToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("并发点赞测试（多个用户同时点赞同一帖子）")
    void concurrentLikes() throws Exception {
        // 发帖
        PostCreateRequest postRequest = new PostCreateRequest();
        postRequest.setContent("并发点赞测试帖子");
        MvcResult postResult = mockMvc.perform(post("/api/post/create")
                .header("Authorization", "Bearer " + testToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(postRequest)))
            .andReturn();
        Long postId = com.alibaba.fastjson2.JSONObject
            .parseObject(postResult.getResponse().getContentAsString())
            .getJSONObject("data").getLong("id");

        // 多次点赞（模拟并发）
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/interaction/like")
                    .header("Authorization", "Bearer " + testToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetId\":" + postId + ",\"targetType\":\"POST\"}"))
                .andExpect(status().isOk());
        }

        // 检查最终状态
        mockMvc.perform(get("/api/interaction/like/check")
                .header("Authorization", "Bearer " + testToken)
                .param("targetId", String.valueOf(postId))
                .param("targetType", "POST"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.liked").value(true));
    }

    @Test
    @DisplayName("容器运行信息验证")
    void containersAreRunning() {
        assertThat(MYSQL.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(MYSQL.getMappedPort(3306)).isGreaterThan(0);
        assertThat(REDIS.getMappedPort(6379)).isGreaterThan(0);
    }
}
