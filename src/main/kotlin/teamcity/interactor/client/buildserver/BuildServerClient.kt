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
import teamcity.interactor.client.ClientFactory
import teamcity.interactor.config.ConfigReader

private data class BuildServerConfig(val baseUrl: String, val buildResource: String)

private val BUILD_SERVER_CONFIG = ConfigReader().config("build-server-config.json", BuildServerConfig::class.java)
val BUILD_SERVER_CLIENT_FACTORY = object : ClientFactory<BuildServerClient> {
    override fun client() =
            Feign.builder()
                    .encoder(JacksonEncoder(ObjectMapper().registerModule(KotlinModule())))
                    .decoder(JacksonDecoder(ObjectMapper()
                            .registerModule(KotlinModule())
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                    .logger(Slf4jLogger(BuildServerClient::class.java))
                    .logLevel(Logger.Level.FULL)
                    .target(BuildServerClient::class.java, "${BUILD_SERVER_CONFIG.baseUrl}${BUILD_SERVER_CONFIG.buildResource}")
}

interface BuildServerClient {
    @RequestLine("GET")
    @Headers("Accept: application/json",
            "Content-Type: application/json")
    fun getBuilds(): List<Build>

    @RequestLine("DELETE")
    @Headers("Content-Type: application/json")
    fun deleteBuild(buildName: BuildName)
}

@JacksonXmlRootElement
data class Build(val id: String, val responseUrl: String)

@JacksonXmlRootElement
data class BuildName(val id: String)
