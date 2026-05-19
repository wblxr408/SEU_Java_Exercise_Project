package com.seu.emotionhub.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seu.emotionhub.model.entity.RecommendationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 推荐日志Mapper
 *
 * @author EmotionHub Team
 */
@Mapper
public interface RecommendationLogMapper extends BaseMapper<RecommendationLog> {

    /**
     * 标记点击（用于 A/B 测试 CTR 统计）
     *
     * @param id 日志ID
     * @return 影响行数
     */
    @Update("UPDATE recommendation_log SET clicked = 1, clicked_at = NOW() WHERE id = #{id} AND clicked = 0")
    int markClicked(@Param("id") Long id);

    /**
     * 批量插入推荐日志（性能优化：将20次insert合并为1次批量操作）
     */
    @Insert("<script>" +
            "INSERT INTO recommendation_log (user_id, post_id, strategy, emotion_state, " +
            "score, position, impressed_at, clicked, user_avg_score, user_volatility, " +
            "trend_type, author_influence) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.userId}, #{item.postId}, #{item.strategy}, #{item.emotionState}, " +
            "#{item.score}, #{item.position}, #{item.impressedAt}, #{item.clicked}, " +
            "#{item.userAvgScore}, #{item.userVolatility}, #{item.trendType}, #{item.authorInfluence})" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatchSomeColumn(@Param("list") List<RecommendationLog> list);
}
