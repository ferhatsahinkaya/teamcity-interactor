
package teamcity.interactor.client.reporting

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.KotlinModule
import feign.Feign
import feign.Headers
import feign.Logger
import feign.RequestLine
import feign.jackson.JacksonEncoder
import feign.slf4j.Slf4jLogger
import teamcity.interactor.client.ClientFactoryForUrl

val REPORTING_CLIENT_FACTORY = object : ClientFactoryForUrl<ReportingClient> {
    override fun client(url: String) =
            Feign.builder()
                    .encoder(JacksonEncoder(ObjectMapper().registerModule(KotlinModule())))
                    .logger(Slf4jLogger(ReportingClient::class.java))
                    .logLevel(Logger.Level.FULL)
                    .target(ReportingClient::class.java, url)
}

interface ReportingClient {
    @RequestLine("POST")
    @Headers("Content-Type: application/json")
    fun report(text: Report)
}

@JacksonXmlRootElement
data class Report(@JsonProperty("blocks") val messages: List<ReportingMessage>)

@JacksonXmlRootElement
data class ReportingMessage(val type: String = "section", val text: Text, @JsonProperty("accessory") val buildStatus: BuildStatus)

@JacksonXmlRootElement
data class Text(val type: String = "mrkdwn", val text: String)

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
enum class BuildStatus(val type: String = "image", @JsonIgnore val state: String, @JsonIgnore val statusPredicate: (String?) -> Boolean, val image_url: String, val alt_text: String) {
    Success(state = "finished", statusPredicate = { it == "SUCCESS" }, image_url = "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png", alt_text = "Success"),
    Failure(state = "finished", statusPredicate = { it != "SUCCESS" }, image_url = "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png", alt_text = "Failure"),
    Running(state = "running", statusPredicate = { true }, image_url = "https://cdn3.iconfinder.com/data/icons/living/24/254_running_activity_fitness-512.png", alt_text = "Running"),
    Queued(state = "queued", statusPredicate = { true }, image_url = "https://cdn1.iconfinder.com/data/icons/company-business-people-1/32/busibess_people-40-512.png", alt_text = "Queued"),
    NotFound(state = "notfound", statusPredicate = { throw UnsupportedOperationException() }, image_url = "https://cdn3.iconfinder.com/data/icons/network-and-communications-8/32/network_Error_lost_no_page_not_found-512.png", alt_text = "Not Found");

    companion object {
        fun of(state: String, status: String?): BuildStatus {
            println("state $state, status $status")
            return values()
                    .first { it.state == state && it.statusPredicate(status) }
        }
    }
}