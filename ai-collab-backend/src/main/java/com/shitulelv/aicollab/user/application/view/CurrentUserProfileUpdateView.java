package com.shitulelv.aicollab.user.application.view;

import com.shitulelv.aicollab.auth.dto.AccountEmailDispatchResult;
import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;

/** 当前用户资料保存结果，附带邮箱变更后的验证邮件状态。 */
public record CurrentUserProfileUpdateView(
        CurrentUserResponse user,
        boolean emailChanged,
        AccountEmailDispatchResult emailDispatch) {
}
