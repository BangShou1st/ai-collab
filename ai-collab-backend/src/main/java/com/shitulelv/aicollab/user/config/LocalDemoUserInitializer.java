package com.shitulelv.aicollab.user.config;

import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 仅在 local Profile 启动时准备演示账号。
 * 它位于应用启动链路，依赖 UserService、PasswordEncoder 和环境配置；先按用户名查询使重复启动保持幂等。
 */
@Component
@Profile("local")
@EnableConfigurationProperties(LocalDemoUserProperties.class)
public class LocalDemoUserInitializer implements ApplicationRunner {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final LocalDemoUserProperties properties;

    public LocalDemoUserInitializer(
            UserService userService,
            PasswordEncoder passwordEncoder,
            LocalDemoUserProperties properties) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * 只在账号不存在时插入，避免每次启动生成新的 BCrypt 盐和重复数据。
     * 密码先做不可逆哈希再交给持久化层，方法中不输出密码或哈希日志。
     */
    @Override
    public void run(ApplicationArguments arguments) {
        if (userService.findByUsername(properties.username()).isPresent()) {
            return;
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(properties.username());
        user.setPasswordHash(passwordEncoder.encode(properties.password()));
        user.setDisplayName("Local Owner");
        user.setStatus(UserStatus.ACTIVE);
        userService.create(user);
    }
}
