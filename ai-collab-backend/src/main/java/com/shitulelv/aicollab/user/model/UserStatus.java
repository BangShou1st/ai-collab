package com.shitulelv.aicollab.user.model;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * app_user.status 的类型安全表示，避免在业务代码中散落容易拼错的状态字符串。
 */
public enum UserStatus {
    ACTIVE("ACTIVE"),
    DISABLED("DISABLED");

    @EnumValue
    private final String databaseValue;

    UserStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String getDatabaseValue() {
        return databaseValue;
    }
}
