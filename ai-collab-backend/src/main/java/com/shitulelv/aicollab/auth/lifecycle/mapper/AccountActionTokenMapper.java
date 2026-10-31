package com.shitulelv.aicollab.auth.lifecycle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.auth.lifecycle.entity.AccountActionTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface AccountActionTokenMapper extends BaseMapper<AccountActionTokenEntity> {

    /**
     * 原子消费：未消费且未过期才置 consumed_at，天然防并发重放。
     */
    @Update("UPDATE account_action_token SET consumed_at = now() "
            + "WHERE token_hash = #{tokenHash} AND token_type = #{tokenType} "
            + "AND consumed_at IS NULL AND expires_at > now()")
    int consumeAtomically(@Param("tokenHash") String tokenHash, @Param("tokenType") String tokenType);

    @Update("UPDATE account_action_token SET consumed_at = now() "
            + "WHERE user_id = #{userId} AND token_type = #{tokenType} AND consumed_at IS NULL")
    int invalidateActiveForUser(@Param("userId") UUID userId, @Param("tokenType") String tokenType);
}
