package com.seu.emotionhub.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 配置
 * 配置静态资源映射等
 *
 * @author EmotionHub Team
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${emotionhub.file.local.base-path:/var/emotionhub/uploads}")
    private String localUploadPath;

    @Value("${emotionhub.file.local.access-url:http://localhost:8080/uploads}")
    private String localAccessUrl;

    /**
     * 配置本地文件上传目录的静态资源映射
     * 将 /uploads/** 映射到服务器文件系统目录
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 本地存储模式：映射 /uploads/** 到文件系统目录
        Path uploadPath = Paths.get(localUploadPath).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath.toString() + "/");
    }
}
