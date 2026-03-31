/**
 * 测试专用 Spring 配置
 *
 * 此配置用于 @SpringBootTest 集成测试：
 *   - 使用 H2 内存数据库（不需要 MySQL）
 *   - 禁用 Redis（使用 @MockBean）
 *   - 禁用 Flyway（测试不需要数据库迁移）
 *   - 配置测试专用的 Security 和 JWT
 */
package com.seu.emotionhub.config;

import com.seu.emotionhub.common.util.JwtUtil;
import com.seu.emotionhub.web.filter.JwtAuthenticationFilter;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.util.Collections;

@TestConfiguration
@EnableAutoConfiguration(exclude = {
    RedisAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class,
    FlywayAutoConfiguration.class,
})
@MapperScan("com.seu.emotionhub.dao.mapper")
public class TestConfig {

    @Bean
    @Primary
    public DataSource testDataSource() {
        return new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("emotionhub_test")
            .build();
    }

    @Bean
    @Primary
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Primary
    public JwtUtil testJwtUtil() {
        // 测试专用的短过期时间 JWT 工具
        return new JwtUtil(
            "EmotionHub_Test_Secret_Key_Must_Be_Long_Enough_For_HS512_Algorithm_Test_Purposes_Only",
            3600000L // 1 hour
        );
    }

    @Bean
    @Primary
    public JwtAuthenticationFilter testJwtAuthenticationFilter(JwtUtil jwtUtil) {
        return new JwtAuthenticationFilter(jwtUtil);
    }

    @Bean
    @Primary
    public WebMvcConfigurer testCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
            }
        };
    }
}
