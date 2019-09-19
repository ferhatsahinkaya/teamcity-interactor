package teamcity.interactor.client.buildserver

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.KotlinModule
import feign.Feign
import feign.Headers
import feign.Logger
import feign.RequestLine
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import feign.slf4j.Slf4jLogger

fun getBuildServerClient(baseUrl: String): BuildServerClient =
        Feign.builder()
                .encoder(JacksonEncoder(ObjectMapper().registerModule(KotlinModule())))
                .decoder(JacksonDecoder(ObjectMapper()
                        .registerModule(KotlinModule())
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .logger(Slf4jLogger(BuildServerClient::class.java))
                .logLevel(Logger.Level.FULL)
                .target(BuildServerClient::class.java, baseUrl)

interface BuildServerClient {
    @RequestLine("GET /build")
    @Headers("Accept: application/json")
    fun getBuildRequests(): List<BuildRequest>

    @RequestLine("DELETE /build")
    @Headers("Content-Type: application/json")
    fun deleteBuildRequest(buildName: BuildName)

    @RequestLine("GET /cancel")
    @Headers("Accept: application/json")
    fun getCancelRequests(): List<CancelRequest>

    @RequestLine("DELETE /cancel")
    @Headers("Content-Type: application/json")
    fun deleteCancelRequest(buildName: BuildName)
}

@JacksonXmlRootElement
data class CancelRequest(val id: String, val responseUrl: String)

@JacksonXmlRootElement
data class BuildRequest(val id: String, val responseUrl: String)

@JacksonXmlRootElement
data class BuildName(val id: String)
