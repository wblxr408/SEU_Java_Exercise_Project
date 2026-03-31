package com.seu.emotionhub.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置属性
 * 支持三种模式：
 * - local: 本地文件系统存储
 * - cos: 腾讯云COS对象存储
 * - oss: 阿里云OSS对象存储
 *
 * @author EmotionHub Team
 */
@Data
@Component
@ConfigurationProperties(prefix = "emotionhub.file")
public class FileStorageProperties {

    /**
     * 存储模式：local / cos / oss
     */
    private String mode = "local";

    /**
     * 本地存储配置
     */
    private Local local = new Local();

    /**
     * 腾讯云COS配置
     */
    private Cos cos = new Cos();

    /**
     * 阿里云OSS配置
     */
    private Oss oss = new Oss();

    @Data
    public static class Local {
        /**
         * 本地存储根目录
         */
        private String basePath = "/var/emotionhub/uploads";

        /**
         * 访问基础URL（前端通过此地址访问图片）
         */
        private String accessUrl = "http://localhost:8080/uploads";
    }

    @Data
    public static class Cos {
        /**
         * 腾讯云 SecretId
         */
        private String secretId;

        /**
         * 腾讯云 SecretKey
         */
        private String secretKey;

        /**
         * COS Bucket 名称
         */
        private String bucket;

        /**
         * COS 地域（如 ap-guangzhou）
         */
        private String region;

        /**
         * 自定义域名（可选，用于CDN加速等）
         */
        private String domain;

        /**
         * 文件在Bucket中的存储路径前缀
         */
        private String prefix = "images/";
    }

    @Data
    public static class Oss {
        /**
         * 阿里云 AccessKey Id
         */
        private String accessKeyId;

        /**
         * 阿里云 AccessKey Secret
         */
        private String accessKeySecret;

        /**
         * OSS Bucket 名称
         */
        private String bucket;

        /**
         * OSS 地域（如 cn-hangzhou）
         */
        private String region;

        /**
         * 自定义域名（可选，用于CDN加速等）
         */
        private String domain;

        /**
         * 文件在Bucket中的存储路径前缀
         */
        private String prefix = "images/";
    }
}
