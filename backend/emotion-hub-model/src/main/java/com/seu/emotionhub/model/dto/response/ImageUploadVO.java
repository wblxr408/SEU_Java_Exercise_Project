package com.seu.emotionhub.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片上传响应DTO
 *
 * @author EmotionHub Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageUploadVO {

    /**
     * 上传成功后的图片访问URL
     */
    private String url;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文件大小（字节）
     */
    private Long size;
}
