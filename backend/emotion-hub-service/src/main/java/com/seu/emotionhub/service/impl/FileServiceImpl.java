package com.seu.emotionhub.service.impl;

import com.seu.emotionhub.service.FileService;
import com.seu.emotionhub.service.config.FileStorageProperties;
import com.seu.emotionhub.service.config.FileStorageProperties.Cos;
import com.seu.emotionhub.service.config.FileStorageProperties.Local;
import com.seu.emotionhub.service.config.FileStorageProperties.Oss;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

/**
 * 文件存储服务实现
 * 支持本地存储和腾讯云COS两种模式
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileStorageProperties fileProps;

    private static final String[] ALLOWED_IMAGE_TYPES = {
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    };
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    @Override
    public String uploadImage(MultipartFile file) {
        validateImage(file);
        String mode = (fileProps.getMode() == null) ? "local" : fileProps.getMode().toLowerCase();
        switch (mode) {
            case "cos" -> { return uploadToCos(file); }
            case "oss" -> { return uploadToOss(file); }
            default -> { return uploadToLocal(file); }
        }
    }

    @Override
    public String[] uploadImages(MultipartFile[] files) {
        return Arrays.stream(files)
                .filter(f -> !f.isEmpty())
                .map(this::uploadImage)
                .toArray(String[]::new);
    }

    @Override
    public void deleteImage(String url) {
        if (url == null || url.isBlank()) return;
        String mode = (fileProps.getMode() == null) ? "local" : fileProps.getMode().toLowerCase();
        switch (mode) {
            case "cos" -> deleteFromCos(url);
            case "oss" -> deleteFromOss(url);
            default -> deleteFromLocal(url);
        }
    }

    @Override
    public String registerUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("图片URL不能为空");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("图片URL格式不正确，必须以 http:// 或 https:// 开头");
        }
        log.info("注册外部图片URL: {}", trimmed);
        return trimmed;
    }

    // ==================== 本地存储实现 ====================

    private String uploadToLocal(MultipartFile file) {
        Local local = fileProps.getLocal();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String filename = generateFilename(file.getOriginalFilename());
        Path targetDir = Paths.get(local.getBasePath(), datePath);
        Path targetPath = targetDir.resolve(filename);

        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetPath.toFile());
            log.info("图片上传至本地: {}", targetPath);
            return local.getAccessUrl().replaceAll("/$", "") + "/" + datePath + "/" + filename;
        } catch (IOException e) {
            log.error("本地文件写入失败: {}", targetPath, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    private void deleteFromLocal(String url) {
        Local local = fileProps.getLocal();
        String relativePath = url.replace(local.getAccessUrl().replaceAll("/$", ""), "");
        Path filePath = Paths.get(local.getBasePath(), relativePath);
        try {
            Files.deleteIfExists(filePath);
            log.info("删除本地文件: {}", filePath);
        } catch (IOException e) {
            log.warn("删除本地文件失败: {}", filePath, e);
        }
    }

    // ==================== 腾讯云COS存储实现 ====================

    private String uploadToCos(MultipartFile file) {
        Cos cos = fileProps.getCos();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String filename = generateFilename(file.getOriginalFilename());
        String cosKey = cos.getPrefix() + datePath + "/" + filename;

        try {
            com.qcloud.cos.COSClient cosClient = createCosClient();
            try {
            com.qcloud.cos.model.ObjectMetadata metadata = new com.qcloud.cos.model.ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(file.getContentType());
            com.qcloud.cos.model.PutObjectRequest putReq =
                    new com.qcloud.cos.model.PutObjectRequest(
                            cos.getBucket(), cosKey, file.getInputStream(), metadata);
                cosClient.putObject(putReq);
                log.info("图片上传至COS: bucket={}, key={}", cos.getBucket(), cosKey);
                String baseUrl = cos.getDomain() != null && !cos.getDomain().isBlank()
                        ? cos.getDomain() : "https://" + cos.getBucket() + ".cos." + cos.getRegion() + ".myqcloud.com";
                return baseUrl.replaceAll("/$", "") + "/" + cosKey;
            } finally {
                cosClient.shutdown();
            }
        } catch (Exception e) {
            log.error("COS上传失败: {}", cosKey, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    private void deleteFromCos(String url) {
        if (url == null) return;
        Cos cos = fileProps.getCos();
        String cosKey = extractCosKey(url, cos);
        if (cosKey == null) return;
        try {
            com.qcloud.cos.COSClient cosClient = createCosClient();
            try {
                cosClient.deleteObject(new com.qcloud.cos.model.DeleteObjectRequest(cos.getBucket(), cosKey));
                log.info("删除COS文件: bucket={}, key={}", cos.getBucket(), cosKey);
            } finally {
                cosClient.shutdown();
            }
        } catch (Exception e) {
            log.warn("删除COS文件失败: {}", url, e);
        }
    }

    private com.qcloud.cos.COSClient createCosClient() {
        Cos cos = fileProps.getCos();
        com.qcloud.cos.ClientConfig clientConfig = new com.qcloud.cos.ClientConfig(
                new com.qcloud.cos.region.Region(cos.getRegion()));
        return new com.qcloud.cos.COSClient(
                new com.qcloud.cos.auth.BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey()),
                clientConfig);
    }

    private String extractCosKey(String url, Cos cos) {
        try {
            String baseUrl = cos.getDomain() != null && !cos.getDomain().isBlank()
                    ? cos.getDomain() : "https://" + cos.getBucket() + ".cos." + cos.getRegion() + ".myqcloud.com";
            if (url.startsWith(baseUrl)) {
                return url.substring(baseUrl.length()).replaceAll("^/", "");
            }
            if (url.contains(cos.getBucket())) {
                int idx = url.indexOf(cos.getPrefix());
                if (idx > 0) return url.substring(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== 阿里云OSS存储实现 ====================

    private String uploadToOss(MultipartFile file) {
        Oss oss = fileProps.getOss();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String filename = generateFilename(file.getOriginalFilename());
        String ossKey = oss.getPrefix() + datePath + "/" + filename;

        OSS ossClient = createOssClient(oss);
        try {
            ossClient.putObject(oss.getBucket(), ossKey, file.getInputStream());
            log.info("图片上传至OSS: bucket={}, key={}", oss.getBucket(), ossKey);
            String baseUrl = oss.getDomain() != null && !oss.getDomain().isBlank()
                    ? oss.getDomain() : "https://" + oss.getBucket() + ".oss-" + oss.getRegion() + ".aliyuncs.com";
            return baseUrl.replaceAll("/$", "") + "/" + ossKey;
        } catch (Exception e) {
            log.error("OSS上传失败: {}", ossKey, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        } finally {
            ossClient.shutdown();
        }
    }

    private void deleteFromOss(String url) {
        if (url == null) return;
        Oss oss = fileProps.getOss();
        String ossKey = extractOssKey(url, oss);
        if (ossKey == null) return;
        try {
            OSS ossClient = createOssClient(oss);
            try {
                ossClient.deleteObject(oss.getBucket(), ossKey);
                log.info("删除OSS文件: bucket={}, key={}", oss.getBucket(), ossKey);
            } finally {
                ossClient.shutdown();
            }
        } catch (Exception e) {
            log.warn("删除OSS文件失败: {}", url, e);
        }
    }

    private OSS createOssClient(Oss oss) {
        String endpoint = "https://oss-" + oss.getRegion() + ".aliyuncs.com";
        return new com.aliyun.oss.OSSClientBuilder().build(
                endpoint, oss.getAccessKeyId(), oss.getAccessKeySecret());
    }

    private String extractOssKey(String url, Oss oss) {
        try {
            String baseUrl = oss.getDomain() != null && !oss.getDomain().isBlank()
                    ? oss.getDomain() : "https://" + oss.getBucket() + ".oss-" + oss.getRegion() + ".aliyuncs.com";
            if (url.startsWith(baseUrl)) {
                return url.substring(baseUrl.length()).replaceAll("^/", "");
            }
            if (url.contains(oss.getBucket())) {
                int idx = url.indexOf(oss.getPrefix());
                if (idx > 0) return url.substring(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== 工具方法 ====================

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("单张图片大小不能超过10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !Arrays.asList(ALLOWED_IMAGE_TYPES).contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("仅支持 JPG/PNG/GIF/WEBP 格式的图片");
        }
    }

    private String generateFilename(String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return UUID.randomUUID().toString().replace("-", "") + ext;
    }
}
