package com.seu.emotionhub.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 * 支持本地存储和云存储（腾讯云COS/阿里云OSS等）
 *
 * @author EmotionHub Team
 */
public interface FileService {

    /**
     * 上传图片
     *
     * @param file MultipartFile
     * @return 访问URL
     */
    String uploadImage(MultipartFile file);

    /**
     * 批量上传图片
     *
     * @param files MultipartFile数组
     * @return 访问URL数组
     */
    String[] uploadImages(MultipartFile[] files);

    /**
     * 删除图片
     *
     * @param url 图片URL
     */
    void deleteImage(String url);

    /**
     * 注册外部URL（用于前端已上传至OSS/COS等，URL直接入库）
     * 仅校验URL合法性，不做文件操作
     *
     * @param url 外部图片URL
     * @return 与 uploadImage 格式一致的访问URL
     */
    String registerUrl(String url);
}
