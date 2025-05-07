package tkimsan.kafkapausepoc.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tkimsan.kafkapausepoc.service.SimulatedDownstreamService

@RestController
@RequestMapping("/control")
class ControlController(
    private val simulatedDownstreamService: SimulatedDownstreamService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${poc.kafka.topic-name}") private val topicName: String
) {

    @PostMapping("/service/unavailable")
    fun setServiceUnavailable(@RequestParam unavailable: Boolean): ResponseEntity<String> {
        simulatedDownstreamService.setServiceUnavailable(unavailable)
        return ResponseEntity.ok("Simulated service unavailable status set to: $unavailable")
    }

    @GetMapping("/service/status")
    fun getServiceStatus(): ResponseEntity<String> {
        return ResponseEntity.ok("Simulated service is currently unavailable: ${simulatedDownstreamService.isServiceUnavailable()}")
    }

    @PostMapping("/send")
    fun sendMessage(@RequestParam message: String, @RequestParam(required = false) key: String?): ResponseEntity<String> {
        kafkaTemplate.send(topicName, key ?: "defaultKey", message)
        return ResponseEntity.ok("Message '$message' sent to topic '$topicName'")
    }
}