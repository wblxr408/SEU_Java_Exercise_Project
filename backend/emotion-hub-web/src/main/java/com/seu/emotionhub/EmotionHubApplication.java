package com.seu.emotionhub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * EmotionHub 应用启动类
 *
 * @author EmotionHub Team
 */
@Slf4j
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@ComponentScan(basePackages = {
        "com.seu.emotionhub.web",
        "com.seu.emotionhub.service",
        "com.seu.emotionhub.common",
        "com.seu.emotionhub.dao",
        "com.seu.emotionhub.model"
})
public class EmotionHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmotionHubApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println("========================================");
        System.out.println("EmotionHub 启动成功！");
        System.out.println("API文档地址: http://localhost:8080/api/doc.html");
        System.out.println("健康检查: http://localhost:8080/api/test/hello");
        System.out.println("========================================");
    }
}
