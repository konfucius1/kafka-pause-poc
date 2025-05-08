package tkimsan.kafkapausepoc.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class ConsumerControlService(
    private val kafkaListenerEndpointRegistry: KafkaListenerEndpointRegistry,
    @Value("\${poc.kafka.consumer.listener-id}") private val listenerId: String,
    @Value("\${poc.kafka.consumer.pause-duration-ms}") private val pauseDurationMs: Long,
) {

    private val logger = KotlinLogging.logger {}
    private val scheduler = Executors.newSingleThreadScheduledExecutor() // Simple scheduler for POC

    fun pauseConsumer() {
        val listenerContainer = getListenerContainer()
        if (listenerContainer != null && listenerContainer.isRunning && !listenerContainer.isPauseRequested) {
            logger.warn("Pausing Kafka consumer (id: {}) for {} ms.", listenerId, pauseDurationMs)
            listenerContainer.pause()
            scheduleResumeConsumer()
        } else if (listenerContainer?.isPauseRequested == true || listenerContainer?.isContainerPaused == true) {
            logger.info("Kafka consumer (id: {}) is already paused or pause requested. Rescheduling resume if needed.", listenerId)
            // If it's already paused by a previous event, ensure a resume is scheduled
            // This simplistic version just schedules a new resume, a more robust one might cancel existing ones
            scheduleResumeConsumer()
        } else {
            logger.info("Kafka consumer (id: {}) is not running or not found, no action to pause.", listenerId)
        }
    }

    private fun scheduleResumeConsumer() {
        logger.info("Scheduling Kafka consumer (id: {}) to resume in {} ms.", listenerId, pauseDurationMs)
        scheduler.schedule({
            resumeConsumer()
        }, pauseDurationMs, TimeUnit.MILLISECONDS)
    }

    private fun resumeConsumer() {
        val listenerContainer = getListenerContainer()
        if (listenerContainer != null && listenerContainer.isContainerPaused) {
            logger.info("Resuming Kafka consumer (id: {}).", listenerId)
            listenerContainer.resume()
        } else {
            logger.warn("Could not resume Kafka consumer (id: {}). Container not found or not paused.", listenerId)
        }
    }

    private fun getListenerContainer(): MessageListenerContainer? {
        return kafkaListenerEndpointRegistry.getListenerContainer(listenerId)
    }
}