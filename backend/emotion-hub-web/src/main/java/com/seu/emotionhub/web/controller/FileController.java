package com.seu.emotionhub.web.controller;

import com.seu.emotionhub.common.result.Result;
import com.seu.emotionhub.model.dto.response.ImageUploadVO;
import com.seu.emotionhub.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传Controller
 *
 * @author EmotionHub Team
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Tag(name = "文件管理", description = "图片上传、删除等文件操作")
public class FileController {

    private final FileService fileService;

    /**
     * 上传单张图片
     */
    @PostMapping("/upload")
    @Operation(summary = "上传图片", description = "上传单张图片，返回访问URL")
    public Result<ImageUploadVO> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("图片上传请求: filename={}, size={}", file.getOriginalFilename(), file.getSize());
        String url = fileService.uploadImage(file);
        ImageUploadVO vo = new ImageUploadVO(url, file.getOriginalFilename(), file.getSize());
        return Result.success("上传成功", vo);
    }

    /**
     * 批量上传图片（最多9张）
     */
    @PostMapping("/upload/batch")
    @Operation(summary = "批量上传图片", description = "一次上传多张图片（最多9张），返回URL数组")
    public Result<ImageUploadVO[]> uploadImagesBatch(@RequestParam("files") MultipartFile[] files) {
        if (files.length > 9) {
            return Result.error(400, "最多上传9张图片");
        }
        log.info("批量图片上传请求: count={}", files.length);
        ImageUploadVO[] results = new ImageUploadVO[files.length];
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            if (!file.isEmpty()) {
                String url = fileService.uploadImage(file);
                results[i] = new ImageUploadVO(url, file.getOriginalFilename(), file.getSize());
            }
        }
        return Result.success("上传成功", results);
    }

    /**
     * 注册外部URL（用于前端直传阿里云OSS等，URL直接入库）
     */
    @PostMapping("/register-url")
    @Operation(summary = "注册外部图片URL", description = "前端上传到OSS后，将URL注册到系统，返回标准ImageUploadVO")
    public Result<ImageUploadVO> registerUrl(@RequestParam("url") String url) {
        log.info("注册外部图片URL请求: url={}", url);
        String registeredUrl = fileService.registerUrl(url);
        ImageUploadVO vo = new ImageUploadVO(registeredUrl, null, null);
        return Result.success("注册成功", vo);
    }

    /**
     * 批量注册外部URL
     */
    @PostMapping("/register-url/batch")
    @Operation(summary = "批量注册外部图片URL", description = "一次注册多张图片URL，返回ImageUploadVO数组")
    public Result<ImageUploadVO[]> registerUrlsBatch(@RequestParam("urls") String[] urls) {
        if (urls.length > 9) {
            return Result.error(400, "最多注册9张图片");
        }
        log.info("批量注册外部图片URL请求: count={}", urls.length);
        ImageUploadVO[] results = new ImageUploadVO[urls.length];
        for (int i = 0; i < urls.length; i++) {
            if (urls[i] != null && !urls[i].isBlank()) {
                String registeredUrl = fileService.registerUrl(urls[i]);
                results[i] = new ImageUploadVO(registeredUrl, null, null);
            }
        }
        return Result.success("注册成功", results);
    }

    /**
     * 删除图片
     */
    @DeleteMapping
    @Operation(summary = "删除图片", description = "根据URL删除已上传的图片")
    public Result<Void> deleteImage(@RequestParam("url") String url) {
        log.info("删除图片请求: url={}", url);
        fileService.deleteImage(url);
        return Result.success("删除成功", null);
    }
}
