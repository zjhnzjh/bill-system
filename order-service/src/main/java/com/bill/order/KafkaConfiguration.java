package com.bill.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
public class KafkaConfiguration {
    @Bean NewTopic orderCreateCommand() { return topic(KafkaTopics.COMMAND); }
    @Bean NewTopic orderCreateRetry() { return topic(KafkaTopics.RETRY); }
    @Bean NewTopic orderCreateDlq() { return topic(KafkaTopics.DLQ); }
    @Bean NewTopic orderStatusEvent() { return topic(KafkaTopics.STATUS); }
    private NewTopic topic(String name) { return TopicBuilder.name(name).partitions(3).replicas(1).build(); }
}
