package com.seu.emotionhub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seu.emotionhub.common.enums.ErrorCode;
import com.seu.emotionhub.common.exception.BusinessException;
import com.seu.emotionhub.dao.mapper.CommentMapper;
import com.seu.emotionhub.dao.mapper.LikeRecordMapper;
import com.seu.emotionhub.dao.mapper.PostMapper;
import com.seu.emotionhub.dao.mapper.UserMapper;
import com.seu.emotionhub.model.dto.request.CommentCreateRequest;
import com.seu.emotionhub.model.dto.response.CommentVO;
import com.seu.emotionhub.model.entity.Comment;
import com.seu.emotionhub.model.entity.LikeRecord;
import com.seu.emotionhub.model.entity.Post;
import com.seu.emotionhub.model.entity.User;
import com.seu.emotionhub.model.enums.PostStatus;
import com.seu.emotionhub.model.enums.TargetType;
import com.seu.emotionhub.service.InteractionService;
import com.seu.emotionhub.service.NotificationService;
import com.seu.emotionhub.service.SentimentPropagationService;
import com.seu.emotionhub.service.cache.CacheService;
import com.seu.emotionhub.service.cache.HotPostCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 互动服务实现类
 *
 * @author EmotionHub Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionServiceImpl implements InteractionService {

    private final LikeRecordMapper likeRecordMapper;
    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final SentimentPropagationService sentimentPropagationService;
    private final CacheService cacheService;
    private final HotPostCacheService hotPostCacheService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleLike(Long targetId, String targetType) {
        Long userId = getCurrentUserId();

        // 校验目标类型
        if (!TargetType.POST.getCode().equals(targetType) &&
                !TargetType.COMMENT.getCode().equals(targetType)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "无效的目标类型");
        }

        if (TargetType.POST.getCode().equals(targetType)) {
            requirePublishedPost(targetId);
        } else {
            Comment comment = requireAvailableComment(targetId);
            requirePublishedPost(comment.getPostId());
        }

        // 查询是否已点赞
        LambdaQueryWrapper<LikeRecord> query = new LambdaQueryWrapper<>();
        query.eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetId, targetId)
                .eq(LikeRecord::getTargetType, targetType);

        LikeRecord existingLike = likeRecordMapper.selectOne(query);

        if (existingLike != null) {
            // 已点赞，执行取消点赞
            likeRecordMapper.deleteById(existingLike.getId());
            updateLikeCount(targetId, targetType, -1);
            log.info("取消点赞: userId={}, targetId={}, targetType={}", userId, targetId, targetType);
            refreshPostCacheIfNeeded(targetId, targetType, "like");
            return false;
        } else {
            // 未点赞，执行点赞
            LikeRecord likeRecord = new LikeRecord();
            likeRecord.setUserId(userId);
            likeRecord.setTargetId(targetId);
            likeRecord.setTargetType(targetType);
            try {
                likeRecordMapper.insert(likeRecord);
                updateLikeCount(targetId, targetType, 1);
                log.info("点赞成功: userId={}, targetId={}, targetType={}", userId, targetId, targetType);
                sendLikeNotification(userId, targetId, targetType);
                refreshPostCacheIfNeeded(targetId, targetType, "like");
                return true;
            } catch (Exception e) {
                // 并发情况下可能重复插入，检查是否已存在
                if (e instanceof org.springframework.dao.DuplicateKeyException ||
                        (e.getCause() != null && e.getCause().getMessage() != null &&
                         e.getCause().getMessage().contains("Duplicate entry"))) {
                    // 重复点赞，视为幂等成功
                    log.info("点赞已存在(幂等): userId={}, targetId={}, targetType={}", userId, targetId, targetType);
                    return true;
                }
                throw e;
            }
        }
    }

    @Override
    public boolean isLiked(Long targetId, String targetType) {
        Long userId = getCurrentUserId();

        LambdaQueryWrapper<LikeRecord> query = new LambdaQueryWrapper<>();
        query.eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetId, targetId)
                .eq(LikeRecord::getTargetType, targetType);

        return likeRecordMapper.selectCount(query) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentVO createComment(CommentCreateRequest request) {
        Long userId = getCurrentUserId();

        Post post = requirePublishedPost(request.getPostId());

        // 如果是回复评论，校验父评论是否存在
        if (request.getParentId() != null) {
            Comment parentComment = requireAvailableComment(request.getParentId());
            if (!parentComment.getPostId().equals(request.getPostId())) {
                throw new BusinessException(ErrorCode.PARENT_COMMENT_NOT_FOUND);
            }
        }

        // 创建评论
        Comment comment = new Comment();
        comment.setPostId(request.getPostId());
        comment.setUserId(userId);
        comment.setParentId(request.getParentId());
        comment.setContent(request.getContent());
        comment.setLikeCount(0);
        comment.setDeleted(false);

        commentMapper.insert(comment);

        // 更新帖子评论数（使用原子操作避免并发死锁）
        postMapper.incrementCommentCount(post.getId(), 1);
        refreshPostCacheIfNeeded(post.getId(), TargetType.POST.getCode(), "comment");

        log.info("发表评论成功: userId={}, postId={}, commentId={}", userId, request.getPostId(), comment.getId());

        // 发送评论通知给帖子作者或被回复者
        sendCommentNotification(userId, post, comment, request.getParentId());

        // 异步记录情感传播（如果评论已完成情感分析）
        // 注意：评论创建时情感分析可能异步进行，这里先尝试记录
        // 如果评论情感分数尚未计算，propagationService会记录日志并跳过
        try {
            sentimentPropagationService.recordCommentPropagation(request.getPostId(), comment.getId());
        } catch (Exception e) {
            log.warn("记录评论情感传播失败（非致命错误）: commentId={}", comment.getId(), e);
        }

        return convertToCommentVO(comment);
    }

    @Override
    public List<CommentVO> listComments(Long postId) {
        requirePublishedPost(postId);

        // 查询所有评论
        LambdaQueryWrapper<Comment> query = new LambdaQueryWrapper<>();
        query.eq(Comment::getPostId, postId)
                .eq(Comment::getDeleted, false)
                .orderByAsc(Comment::getCreatedAt);

        List<Comment> comments = commentMapper.selectList(query);

        // 转换为VO并构建树形结构
        List<CommentVO> commentVOList = comments.stream()
                .map(this::convertToCommentVO)
                .collect(Collectors.toList());

        return buildCommentTree(commentVOList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId) {
        deleteCommentInternal(commentId, getCurrentUserId(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminDeleteComment(Long commentId) {
        deleteCommentInternal(commentId, getCurrentUserId(), true);
    }

    private void refreshPostCacheIfNeeded(Long targetId, String targetType, String action) {
        if (TargetType.POST.getCode().equals(targetType)) {
            cacheService.delete(CacheService.CacheKey.POST_DETAIL + targetId);
            if (action != null) {
                hotPostCacheService.updateHotScore(targetId, action);
            }
        }
    }

    @Override
    public CommentVO getComment(Long commentId) {
        return convertToCommentVO(requireAvailableComment(commentId));
    }

    /**
     * 更新点赞数（使用原子操作避免并发死锁）
     */
    private void updateLikeCount(Long targetId, String targetType, int delta) {
        if (TargetType.POST.getCode().equals(targetType)) {
            // 使用原子操作更新，避免并发死锁
            postMapper.incrementLikeCount(targetId, delta);
        } else if (TargetType.COMMENT.getCode().equals(targetType)) {
            // 使用原子操作更新评论点赞数，避免并发死锁
            commentMapper.incrementLikeCount(targetId, delta);
        }
    }

    /**
     * 删除评论及其所有子评论
     */
    private void deleteCommentInternal(Long commentId, Long operatorId, boolean adminOperation) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(comment.getDeleted())) {
            throw new BusinessException(ErrorCode.COMMENT_DELETED);
        }

        if (!adminOperation && !comment.getUserId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_OWNER);
        }

        int deletedCount = softDeleteCommentAndChildren(commentId);

        // 使用原子操作减少评论数，避免并发死锁
        postMapper.decrementCommentCount(comment.getPostId(), deletedCount);
        refreshPostCacheIfNeeded(comment.getPostId(), TargetType.POST.getCode(), deletedCount > 0 ? "comment" : null);

        log.info("{}删除评论成功: operatorId={}, commentId={}, deletedCount={}",
                adminOperation ? "管理员" : "用户", operatorId, commentId, deletedCount);
    }

    private int softDeleteCommentAndChildren(Long commentId) {
        Comment current = commentMapper.selectById(commentId);
        if (current == null || Boolean.TRUE.equals(current.getDeleted())) {
            return 0;
        }

        current.setDeleted(true);
        commentMapper.updateById(current);
        int deletedCount = 1;

        LambdaQueryWrapper<Comment> query = new LambdaQueryWrapper<>();
        query.eq(Comment::getParentId, commentId)
                .eq(Comment::getDeleted, false);
        List<Comment> children = commentMapper.selectList(query);
        for (Comment child : children) {
            deletedCount += softDeleteCommentAndChildren(child.getId());
        }
        return deletedCount;
    }

    /**
     * 构建评论树形结构
     */
    private List<CommentVO> buildCommentTree(List<CommentVO> allComments) {
        Map<Long, CommentVO> commentMap = new HashMap<>();
        List<CommentVO> rootComments = new ArrayList<>();

        // 第一遍：构建Map
        for (CommentVO comment : allComments) {
            commentMap.put(comment.getId(), comment);
            comment.setChildren(new ArrayList<>());
        }

        // 第二遍：构建树形结构
        for (CommentVO comment : allComments) {
            if (comment.getParentId() == null) {
                // 根评论
                rootComments.add(comment);
            } else {
                // 子评论
                CommentVO parent = commentMap.get(comment.getParentId());
                if (parent != null) {
                    parent.getChildren().add(comment);
                }
            }
        }

        return rootComments;
    }

    /**
     * 转换为CommentVO
     */
    private CommentVO convertToCommentVO(Comment comment) {
        CommentVO vo = new CommentVO();
        BeanUtils.copyProperties(comment, vo);

        // 查询评论者信息
        User user = userMapper.selectById(comment.getUserId());
        if (user != null) {
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }

        // 检查当前用户是否已点赞
        try {
            vo.setLiked(isLiked(comment.getId(), TargetType.COMMENT.getCode()));
        } catch (Exception e) {
            vo.setLiked(false);
        }

        return vo;
    }

    /**
     * 发送点赞通知
     */
    private void sendLikeNotification(Long likerId, Long targetId, String targetType) {
        try {
            if (TargetType.POST.getCode().equals(targetType)) {
                // 点赞帖子
                Post post = postMapper.selectById(targetId);
                if (post != null && !post.getUserId().equals(likerId)) {
                    User liker = userMapper.selectById(likerId);
                    String title = "New Like";
                    String content = String.format("%s liked your post", liker != null ? liker.getNickname() : "Someone");
                    notificationService.createNotification(post.getUserId(), "LIKE", title, content, targetId);
                }
            } else if (TargetType.COMMENT.getCode().equals(targetType)) {
                // 点赞评论
                Comment comment = commentMapper.selectById(targetId);
                if (comment != null && !comment.getUserId().equals(likerId)) {
                    User liker = userMapper.selectById(likerId);
                    String title = "New Like";
                    String content = String.format("%s liked your comment", liker != null ? liker.getNickname() : "Someone");
                    notificationService.createNotification(comment.getUserId(), "LIKE", title, content, comment.getPostId());
                }
            }
        } catch (Exception e) {
            log.error("发送点赞通知失败", e);
        }
    }

    /**
     * 发送评论通知
     */
    private void sendCommentNotification(Long commenterId, Post post, Comment comment, Long parentId) {
        try {
            User commenter = userMapper.selectById(commenterId);
            String commenterName = commenter != null ? commenter.getNickname() : "Someone";

            if (parentId != null) {
                // 回复评论
                Comment parentComment = commentMapper.selectById(parentId);
                if (parentComment != null && !parentComment.getUserId().equals(commenterId)) {
                    String title = "New Reply";
                    String content = String.format("%s replied to your comment", commenterName);
                    notificationService.createNotification(parentComment.getUserId(), "COMMENT", title, content, post.getId());
                }
            } else {
                // 评论帖子
                if (!post.getUserId().equals(commenterId)) {
                    String title = "New Comment";
                    String content = String.format("%s commented on your post", commenterName);
                    notificationService.createNotification(post.getUserId(), "COMMENT", title, content, post.getId());
                }
            }
        } catch (Exception e) {
            log.error("发送评论通知失败", e);
        }
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Long) {
            return (Long) principal;
        }

        throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }

    private Post requirePublishedPost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // 允许 PUBLISHED 和 ANALYZING 状态的帖子进行交互
        // ANALYZING 状态表示帖子已创建且正在分析，用户应能立即互动
        if (PostStatus.DELETED.getCode().equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.POST_DELETED);
        }
        if (!PostStatus.PUBLISHED.getCode().equals(post.getStatus()) 
                && !PostStatus.ANALYZING.getCode().equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "帖子当前不可交互");
        }
        return post;
    }

    private Comment requireAvailableComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(comment.getDeleted())) {
            throw new BusinessException(ErrorCode.COMMENT_DELETED);
        }
        return comment;
    }
}
