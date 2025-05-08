package tkimsan.kafkapausepoc.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
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
    data class ApiResponse(
        val status: String,
        val message: String,
    )

    @PostMapping("/service/unavailable")
    fun setServiceUnavailable(@RequestParam unavailable: Boolean): ResponseEntity<ApiResponse> {
        simulatedDownstreamService.setServiceUnavailable(unavailable)
        val response = ApiResponse(
            status = "success",
            message = "Simulated service unavailable status set to: $unavailable"
        )
        return ResponseEntity.ok(response)
    }

    @GetMapping("/service/status")
    fun getServiceStatus(): ResponseEntity<ApiResponse> {
        val isUnavailable = simulatedDownstreamService.isServiceUnavailable()
        val status = if (isUnavailable) "unavailable" else "available"
        val message = "Simulated service is currently $status"
        val response = ApiResponse(
            status = status,
            message = message
        )
        val httpStatus = if (isUnavailable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.OK
        return ResponseEntity.status(httpStatus).body(response)
    }

    @PostMapping("/send")
    fun sendMessage(
        @RequestParam message: String,
        @RequestParam(required = false) key: String?
    ): ResponseEntity<ApiResponse> {
        kafkaTemplate.send(topicName, key ?: "defaultKey", message)
        val response = ApiResponse(
            status = "sent",
            message = "Message '$message' sent to topic '$topicName'"
        )
        return ResponseEntity.ok(response)
    }
}
