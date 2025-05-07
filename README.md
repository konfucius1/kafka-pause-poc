# Kafka Pause POC

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
