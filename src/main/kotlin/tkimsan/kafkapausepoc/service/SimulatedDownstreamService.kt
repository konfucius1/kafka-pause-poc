package tkimsan.kafkapausepoc.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import tkimsan.kafkapausepoc.exception.SimulatedServiceUnavailableException
import java.util.concurrent.atomic.AtomicBoolean

@Service
class SimulatedDownstreamService {

    private val logger = KotlinLogging.logger {}
    private val serviceUnavailable = AtomicBoolean(false)

    fun processMessage(message: String): String {
        if (serviceUnavailable.get()) {
            logger.warn("SimulatedDownstreamService: Service is unavailable (503).")
            throw SimulatedServiceUnavailableException("Service temporarily unavailable (503)")
        }
        logger.info("SimulatedDownstreamService: Successfully processed message: {}", message)
        return "Processed: $message"
    }

    fun setServiceUnavailable(isUnavailable: Boolean) {
        serviceUnavailable.set(isUnavailable)
        logger.info("SimulatedDownstreamService: Service available status set to: {}", !isUnavailable)
    }

    fun isServiceUnavailable(): Boolean = serviceUnavailable.get()
}