package teamcity.interactor.client.reporting

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
data class ReportingMessage(val type: String = "section", val text: Text, val accessory: Accessory)

@JacksonXmlRootElement
data class Text(val type: String = "mrkdwn", val text: String)

@JacksonXmlRootElement
data class Accessory(val type: String = "image", @JsonProperty("image_url") val imageUrl: String, @JsonProperty("alt_text") val altText: String)