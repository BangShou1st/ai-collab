package com.shitulelv.aicollab.knowledge.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeFeedbackEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeFeedbackMapper extends BaseMapper<KnowledgeFeedbackEntity> {

    @Select("SELECT * FROM knowledge_feedback WHERE message_id = #{messageId} AND user_id = #{userId} LIMIT 1")
    KnowledgeFeedbackEntity findByMessageAndUser(
            @Param("messageId") java.util.UUID messageId,
            @Param("userId") java.util.UUID userId);

    @Update("INSERT INTO knowledge_feedback (id, message_id, user_id, helpful, created_at) " +
            "VALUES (gen_random_uuid(), #{messageId}, #{userId}, #{helpful}, now()) " +
            "ON CONFLICT (message_id, user_id) DO UPDATE SET helpful = #{helpful}, created_at = now()")
    int upsert(@Param("messageId") java.util.UUID messageId,
               @Param("userId") java.util.UUID userId,
               @Param("helpful") boolean helpful);

    @Update("DELETE FROM knowledge_feedback WHERE message_id = #{messageId} AND user_id = #{userId}")
    int deleteByMessageAndUser(
            @Param("messageId") java.util.UUID messageId,
            @Param("userId") java.util.UUID userId);

    @Select("SELECT COUNT(*) FROM knowledge_feedback WHERE message_id = #{messageId} AND helpful = true")
    int countHelpful(@Param("messageId") java.util.UUID messageId);

    @Select("SELECT COUNT(*) FROM knowledge_feedback WHERE message_id = #{messageId} AND helpful = false")
    int countUnhelpful(@Param("messageId") java.util.UUID messageId);
}
