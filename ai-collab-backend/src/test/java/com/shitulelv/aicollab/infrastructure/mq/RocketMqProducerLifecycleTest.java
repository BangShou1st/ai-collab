package com.shitulelv.aicollab.infrastructure.mq;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** RocketMQ 生产者选型测试：普通链路不得误建事务生产者，文档链路必须挂载事务监听器。 */
class RocketMqProducerLifecycleTest {

    @Test
    void normalLifecycleCreatesDefaultProducer() {
        RocketMqProducerLifecycle lifecycle =
                new RocketMqProducerLifecycle("normal-group", "127.0.0.1:9876", "测试");

        DefaultMQProducer producer = lifecycle.createProducer();

        assertThat(producer).isExactlyInstanceOf(DefaultMQProducer.class);
        assertThat(producer.getProducerGroup()).isEqualTo("normal-group");
    }

    @Test
    void transactionalLifecycleCreatesTransactionProducerWithListener() {
        TransactionListener listener = mock(TransactionListener.class);
        RocketMqProducerLifecycle lifecycle =
                new RocketMqProducerLifecycle("document-group", "127.0.0.1:9876", "文档链路", listener);

        DefaultMQProducer producer = lifecycle.createProducer();

        assertThat(producer).isInstanceOf(TransactionMQProducer.class);
        TransactionMQProducer transactional = (TransactionMQProducer) producer;
        assertThat(transactional.getProducerGroup()).isEqualTo("document-group");
        assertThat(transactional.getTransactionListener()).isSameAs(listener);
    }
}
