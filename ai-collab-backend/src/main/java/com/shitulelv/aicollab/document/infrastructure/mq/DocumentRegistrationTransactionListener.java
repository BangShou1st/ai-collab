package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * 文档受理事务消息的本地事务监听器（M1 迁移）。
 *
 * <p>半消息对消费者不可见，直到本地注册事务提交（COMMIT）或回滚（ROLLBACK）；
 * 应用在半消息发出后崩溃、事务结果未知时，由 broker 主动回查
 * {@link #checkLocalTransaction}，以文档状态行为事实源做出最终裁决。</p>
 *
 * <p>裁决规则（状态行是唯一事实源）：</p>
 * <ul>
 *   <li>executeLocalTransaction：注册事务成功 → COMMIT；任何异常 → ROLLBACK
 *      （事务已随异常回滚，半消息直接丢弃，异常由发送侧读取并按原语义透传调用方）；</li>
 *   <li>checkLocalTransaction：状态行不存在 → ROLLBACK（注册从未提交，消息无意义）；
 *       状态行存在（无论 UPLOADED / PARSING / INDEXING / READY / FAILED / DELETING）
 *       → COMMIT —— UPLOADED 等待处理、处理中或已终态的重复投递均由消费侧幂等
 *       三重保障丢弃，重复提交消息无害。</li>
 * </ul>
 */
public class DocumentRegistrationTransactionListener implements TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistrationTransactionListener.class);

    private final DocumentRepository documents;
    private final DocumentMqMetrics metrics;

    public DocumentRegistrationTransactionListener(DocumentRepository documents, DocumentMqMetrics metrics) {
        this.documents = documents;
        this.metrics = metrics;
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object argument) {
        DocumentRegistrationExecution execution = (DocumentRegistrationExecution) argument;
        try {
            execution.run();
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (RuntimeException exception) {
            execution.error = exception;
            log.warn("文档注册事务失败，回滚事务消息，projectId={}，documentId={}，type={}",
                    message.getUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID),
                    message.getUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID),
                    exception.getClass().getSimpleName());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        UUID projectId = UUID.fromString(message.getUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID));
        UUID documentId = UUID.fromString(message.getUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID));
        Optional<DocumentEntity> document = documents.find(projectId, documentId);
        if (document.isEmpty()) {
            // 注册事务未提交（或回滚）：丢弃半消息。若恰逢"回查先于事务提交可见"的
            // 竞态窗口，已提交的 UPLOADED 状态行仍会被恢复 Job 对账派发（消息丢失无害）。
            log.info("文档事务消息回查：状态行不存在，回滚半消息，projectId={}，documentId={}",
                    projectId, documentId);
            metrics.sent("rollback");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        log.debug("文档事务消息回查：状态行存在（status={}），提交半消息，documentId={}",
                document.get().getStatus(), documentId);
        return LocalTransactionState.COMMIT_MESSAGE;
    }
}
