package tkimsan.kafkapausepoc.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import tkimsan.kafkapausepoc.exception.SimulatedServiceUnavailableException

@Service
class KafkaConsumerService(
    private val simulatedDownstreamService: SimulatedDownstreamService,
    private val consumerControlService: ConsumerControlService,
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    private val logger = KotlinLogging.logger {}

    // Ensure the ID here matches the one in application.yml and used by ConsumerControlService
    @KafkaListener(
        id = "\${poc.kafka.consumer.listener-id}", // Use SpEL to fetch from properties
        topics = ["\${poc.kafka.topic-name}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun listen(record: ConsumerRecord<String, String>, ack: Acknowledgment) {
        logger.info(
            "Received message - Topic: {}, Partition: {}, Offset: {}, Key: {}, Value: {}",
            record.topic(), record.partition(), record.offset(), record.key(), record.value()
        )

        try {
            val result = simulatedDownstreamService.processMessage(record.key(), record.value())
            logger.info("Message processed successfully: {}", result)
            kafkaTemplate.send("flaky-output-topic", record.key(), record.value()) // Chuck messages in a sink topic for testing
            ack.acknowledge() // Acknowledge message after successful processing
        } catch (e: SimulatedServiceUnavailableException) {
            logger.error(
                "Service unavailable for message - Key: {}, Value: {}. Error: {}. Initiating consumer pause.",
                record.key(), record.value(), e.message
            )
            // DO NOT acknowledge the message as it hasn't been processed.
            // The consumer will re-poll this message after resuming.
            consumerControlService.pauseConsumer()
            throw e
        } catch (e: Exception) {
            logger.error(
                "Unexpected error processing message - Key: {}, Value: {}. Error: {}",
                record.key(), record.value(), e.message, e
            )

            // Handle other unexpected errors, perhaps by sending to a DLT or just acknowledging to skip.
            // For this POC, we'll acknowledge to prevent blocking on unexpected errors, but in real apps, this needs care.
            ack.acknowledge()
        }
    }
}