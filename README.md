# Kafka Pause POC

## Overview

This application demonstrates a proof-of-concept (POC) for pausing and automatically resuming a Spring Kafka consumer. This pattern can be useful when a downstream service, which the Kafka
consumer depends on for message processing, becomes temporarily unavailable. Instead of continuously retrying, overwhelming the downstream service, or logging excessive errors, the consumer
pauses its message processing for a configurable duration and then attempts to resume. The goal is to handle transient downstream issues gracefully.

## Core Mechanism: Consumer Pause and Resume

The main components involved in this pause and resume mechanism are:

1. **`KafkaConsumerService`**:
   * Listens to messages on the configured Kafka topic (`poc.kafka.topic-name`)
   * For each message, it attempts to process it by calling the `SimulateDownstreamService`
   * If `SimulatedDownstreamService` throws a `SimulatedServiceUnavailableException`, the `KafkaConsumerService`
   * Does **not** acknowledge the Kafka message. This is critical, as it ensures the message will be redelivered after the consumer resumes 
   * Calls `ConsumerControlService.pauseConsumer()` to initiate the pause
2. **`SimulatedDownstreamService`**:
   * Mimics a real downstream service that the Kafka consumer might depend on 
   * Its availability can be toggled via an HTTP endpoint (`/control/service/endpoint`)
   * When `processMessage` is called while it's set to "unavailable", it throws a `SimulatedServiceUnavailableException`
3. **`ConsumerControlService`**:
   * Manages the pausing and resuming of the Kafka listener container
   * `pauseConsumer()`:
     * Identifies the target `MessageListenerContainer` using the `poc.kafka.consumer.listener-id`
     * If the container is running and not already paused, it calls `container.pause()`
     * Schedules a task using a `ScheduledExecutorService` to call `resumeConsumer()` after `poc.kafka.consumer.pause-duration-ms`
   * `resumeConsumer()`:
     * Calls `container.resume()` on the target listener container
     * In the current implementation, `resumeConsumer()` also unconditionally sets the `SimulatedDownstreamService` back to "available". In a real-world scenario, the resumption logic would likely re-check the actual downstream service's health before or after resuming the consumer, rather than assuming it's available.
4. **`ControlController`**:
   * Provides HTTP endpoints to:
     * Toggle the availability of the `SimulatedDownstreamService`
     * Check the current status of the `SimulatedDownstreamService`
     * Send messages to the Kafka topic for testing purposes

## Testing

### Initial state

- Check service status: `GET http://localhost:8080/control/service/status`
- Send a message: `POST http://localhost:8080/control/send?message=hello_world_1`
- You should see the message received and processed successfully by `KafkaConsumerService` and `SimulatedDownstreamService`.

### Trigger service unavailability

- Set service to unavailable: `POST http://localhost:8080/control/service/unavailable?unavailable=true`
- Check status: GET http://localhost:8080/control/service/status (Should be unavailable).

### Pausing

- Send another message: POST http://localhost:8080/control/send?message=test_pause_1
- Observe logs:
  - KafkaConsumerService receives the message.
  - SimulatedDownstreamService logs "Service is unavailable (503)". 
  - KafkaConsumerService logs "Service unavailable... Initiating consumer pause."
  - ConsumerControlService logs "Pausing Kafka consumer..." and "Scheduling Kafka consumer... to resume in 30000 ms."
- Try sending another message immediately: `POST http://localhost:8080/control/send?message=test_pause_2_during_pause`
  - This message will sit in Kafka. The consumer is paused and won't pick it up yet.

### Automatic Resumption:

- Wait for the `poc.kafka.consumer.pause-duration-ms` (30 seconds in this config).
- Observe logs: `ConsumerControlService` logs "Resuming Kafka consumer...".
- The consumer should now attempt to re-process test_pause_1 (because it wasn't acknowledged).
- Since the service is still "unavailable", it will again detect 503 and pause again. This demonstrates the cycle. 
- Optional: While it's paused, set the service back to available: `POST http://localhost:8080/control/service/unavailable?unavailable=false`
- When the consumer resumes this time (after the next pause cycle), it should successfully process `test_pause_1`.
- Then it should pick up test_pause_2_during_pause and process it successfully.

### Set service back to available and test normal operation:

- `POST http://localhost:8080/control/service/unavailable?unavailable=false`
- Send a new message: POST http://localhost:8080/control/send?message=hello_again_1
- Observe logs: Message should be processed successfully without any pausing.

### Set service back to available and test normal operation:

- `POST http://localhost:8080/control/service/unavailable?unavailable=false`
- Send a new message: POST http://localhost:8080/control/send?message=hello_again_1
- Observe logs: Message should be processed successfully without any pausing.

## Key Configuration

The primary behavior of the application and the Kafka consumer is configured in `src/main/resources/application.yaml`:

-   `poc.kafka.topic-name`: Specifies the Kafka topic that the `KafkaConsumerService` will listen to.
-   `poc.kafka.consumer.listener-id`: A unique identifier for the Kafka listener. This ID is crucial as the `ConsumerControlService` uses it to find and control the specific listener       
    container (e.g., to pause and resume it).
-   `poc.kafka.consumer.pause-duration-ms`: Defines the duration (in milliseconds) for which the consumer will remain paused after an issue is detected before an automatic resumption       
    attempt is made.
-   `spring.kafka.consumer.group-id`: The Kafka consumer group ID.
-   `spring.kafka.listener.ack-mode`: Set to `manual` (or `manual_immediate`). This is essential for the POC's logic. By setting it to `manual`, the `KafkaConsumerService` receives an      
    `Acknowledgment` object and can explicitly decide whether to acknowledge a message (on success) or not (on failure, allowing it to be re-processed after resume).
-   `spring.kafka.bootstrap-servers`: The address of the Kafka brokers.

## Extensions

- Fixed time delay vs Polling for resumption
- Polling/health check (better way)
- Error handlers (Spring Kafka's `DefaultErrorHandler`)
  - Instead of manually calling `pause()` in the listener, we could configure a `DefaultErrorHandler`
  - This handler can be configured with a `BackOff` strategy. If a message fails processing N times (e.g., due to the 503), the error handler itself can be made to pause the container.
```kotlin
// In a @Configuration class
@Bean
fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<Any, Any>,
    consumerControlService: ConsumerControlService // For custom pause logic
): ConcurrentKafkaListenerContainerFactory<Any, Any> {
    val factory = ConcurrentKafkaListenerContainerFactory<Any, Any>()
    factory.consumerFactory = consumerFactory

    val errorHandler = DefaultErrorHandler({ record, exception ->
        // This lambda is the 'recoverer' called after retries are exhausted
        logger.error("Retries exhausted for record: $record. Pausing consumer.", exception)
        consumerControlService.pauseConsumer() // Or directly pause: factory.getContainerProperties().getListenerTaskExecutor().execute { registry.getListenerContainer("listenerId").pause() }
    }, FixedBackOff(1000L, 2L)) // Retry 2 times with 1s interval

    errorHandler.addRetryableExceptions(SimulatedServiceUnavailableException::class.java)
    // Or add non-retryable and handle pausing differently
    // errorHandler.addNotRetryableExceptions(...)

    factory.setCommonErrorHandler(errorHandler)
    return factory
}
```
  - This approach integrates more naturally with Spring Kafka's error handling mechanisms. The listener method would just throw the SimulatedServiceUnavailableException.
- Idempotency: If a message is processed but the 503 occurs after the actual work is done but before acknowledgement, resuming might cause duplicate processing. Ensure our downstream service processing is idempotent if possible.
- Dead Letter Topic (DLT): For messages that consistently fail even after retries and pauses (e.g., malformed messages, or if the 503 indicates a longer outage, and we don't want to block indefinitely), we should configure a DLT. The DefaultErrorHandler can be configured to send messages to a DLT after retries are exhausted.
- Monitoring: Add Micrometer metrics to track consumer status (running, paused), number of pauses, processing errors, etc.
- Graceful Shutdown: Ensure the `ScheduledExecutorService` in `ConsumerControlService` is properly shut down when the application stops to prevent resource leaks. Using a Spring-managed TaskScheduler often handles this better.
