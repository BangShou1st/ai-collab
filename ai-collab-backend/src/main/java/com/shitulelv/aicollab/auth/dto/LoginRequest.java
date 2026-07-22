package com.shitulelv.aicollab.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录接口输入；record 使请求对象保持不可变且不携带持久化行为。 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 40, message = "用户名长度不能超过 40 个字符")
        String username,
        @NotBlank(message = "密码不能为空")
        String password) {
}
