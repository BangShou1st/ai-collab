package com.shitulelv.aicollab.document.infrastructure.mq;

/**
 * 事务消息的本地事务执行体：作为 arg 随半消息传入
 * {@code TransactionListener.executeLocalTransaction}，在监听器线程内同步执行注册事务。
 * 执行失败时把异常暂存在 {@link #error}，由调用方（发送侧）读取后按原语义透传。
 */
final class DocumentRegistrationExecution {

    private final Runnable registration;

    /** 注册事务抛出的业务/未预期异常；null 表示执行成功。 */
    RuntimeException error;

    /** 本地注册事务已成功提交；broker 回查前发送结果未知时据此禁止重复注册。 */
    boolean committed;

    DocumentRegistrationExecution(Runnable registration) {
        this.registration = registration;
    }

    void run() {
        registration.run();
        committed = true;
    }
}
