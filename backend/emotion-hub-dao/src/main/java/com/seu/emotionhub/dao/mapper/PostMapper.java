package com.seu.emotionhub.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seu.emotionhub.model.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 帖子Mapper接口
 *
 * @author EmotionHub Team
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 增加浏览数（原子操作）
     *
     * @param postId 帖子ID
     * @param count  增加的数量
     * @return 影响行数
     */
    @Update("UPDATE post SET view_count = view_count + #{count} WHERE id = #{postId}")
    int incrementViewCount(@Param("postId") Long postId, @Param("count") Integer count);

    /**
     * 增加点赞数（原子操作）
     *
     * @param postId 帖子ID
     * @param count  增加的数量（正数表示+1，负数表示-1）
     * @return 影响行数
     */
    @Update("UPDATE post SET like_count = like_count + #{count} WHERE id = #{postId}")
    int incrementLikeCount(@Param("postId") Long postId, @Param("count") Integer count);

    /**
     * 增加评论数（原子操作）
     *
     * @param postId 帖子ID
     * @param count  增加的数量
     * @return 影响行数
     */
    @Update("UPDATE post SET comment_count = comment_count + #{count} WHERE id = #{postId}")
    int incrementCommentCount(@Param("postId") Long postId, @Param("count") Integer count);

    /**
     * 减少评论数（原子操作，用于删除评论时）
     *
     * @param postId 帖子ID
     * @param count  减少的数量
     * @return 影响行数
     */
    @Update("UPDATE post SET comment_count = GREATEST(0, comment_count - #{count}) WHERE id = #{postId}")
    int decrementCommentCount(@Param("postId") Long postId, @Param("count") Integer count);

    /**
     * 更新帖子情感分析结果（原子操作，避免并发死锁）
     * 只更新情感相关字段，不影响其他字段
     *
     * @param postId        帖子ID
     * @param emotionLabel  情感标签
     * @param emotionScore  情感分数
     * @param status        帖子状态
     * @return 影响行数
     */
    @Update("UPDATE post SET emotion_label = #{emotionLabel}, emotion_score = #{emotionScore}, status = #{status}, updated_at = NOW() WHERE id = #{postId}")
    int updateEmotionAnalysis(@Param("postId") Long postId, @Param("emotionLabel") String emotionLabel,
                              @Param("emotionScore") java.math.BigDecimal emotionScore, @Param("status") String status);
}
