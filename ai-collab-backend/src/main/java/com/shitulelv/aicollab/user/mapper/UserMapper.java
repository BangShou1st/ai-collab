package com.shitulelv.aicollab.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * app_user 的数据库访问入口。
 * Mapper 只执行持久化操作，不承载登录规则或接口响应组装。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
