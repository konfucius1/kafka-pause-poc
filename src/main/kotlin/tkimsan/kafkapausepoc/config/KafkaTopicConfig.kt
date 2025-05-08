package tkimsan.kafkapausepoc.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig(
    @Value("\${poc.kafka.topic-name}") private val topicName: String
) {

    @Bean
    fun pocTopic(): NewTopic {
        return TopicBuilder.name(topicName)
            .partitions(1)
            .build()
    }

    @Bean
    fun sinkTopic(): NewTopic {
        return TopicBuilder.name("flaky-output-topic")
            .partitions(1)
            .build()
    }
}