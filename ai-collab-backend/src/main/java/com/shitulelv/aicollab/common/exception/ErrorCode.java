package com.shitulelv.aicollab.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 集中定义客户端可依赖的业务错误码、默认提示与 HTTP 语义。
 * 它连接业务异常和 HTTP 层，避免各处自行选择状态码造成契约不一致。
 */
public enum ErrorCode {
    SUCCESS(HttpStatus.OK, "操作成功"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "当前登录状态无效，请重新登录"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行此操作"),
    USER_DISABLED(HttpStatus.FORBIDDEN, "账号已被禁用"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "当前未开放公开注册"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "用户名已存在"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "邮箱已存在"),
    CURRENT_PASSWORD_INVALID(HttpStatus.BAD_REQUEST, "当前密码不正确"),
    NEW_PASSWORD_SAME_AS_CURRENT(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同"),
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "项目不存在"),
    PROJECT_ADMIN_REQUIRED(HttpStatus.FORBIDDEN, "需要项目管理员权限"),
    PROJECT_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "需要项目所有者权限"),
    PROJECT_OWNER_CANNOT_BE_REMOVED(HttpStatus.FORBIDDEN, "项目所有者不能被降级或移除"),
    MEMBER_ALREADY_EXISTS(HttpStatus.CONFLICT, "用户已经是项目成员"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "项目成员不存在"),
    INVITATION_INVALID(HttpStatus.GONE, "邀请无效"),
    INVITATION_EXPIRED(HttpStatus.GONE, "邀请已过期"),
    INVITATION_ALREADY_USED(HttpStatus.GONE, "邀请已被使用"),
    INVITATION_EMAIL_MISMATCH(HttpStatus.FORBIDDEN, "当前账号与邀请邮箱不匹配"),
    MILESTONE_NOT_FOUND(HttpStatus.NOT_FOUND, "里程碑不存在"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "任务不存在"),
    TASK_ASSIGNEE_NOT_MEMBER(HttpStatus.BAD_REQUEST, "负责人不是当前项目成员"),
    TASK_MILESTONE_CROSS_PROJECT(HttpStatus.BAD_REQUEST, "里程碑不属于当前项目"),
    TASK_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "不允许的任务状态转换"),
    TASK_BLOCKED_BY_DEPENDENCY(HttpStatus.CONFLICT, "任务仍有未完成依赖"),
    TASK_DEPENDENCY_CYCLE(HttpStatus.CONFLICT, "任务依赖不能形成环"),
    TASK_DEPENDENCY_CROSS_PROJECT(HttpStatus.BAD_REQUEST, "依赖任务不属于当前项目"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "评论不存在"),
    COMMENT_AUTHOR_REQUIRED(HttpStatus.FORBIDDEN, "只有评论作者可以修改评论"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "数据已被其他请求修改，请刷新后重试"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
