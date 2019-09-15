package teamcity.interactor.client.teamcity

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.KotlinModule
import feign.*
import feign.auth.BasicAuthRequestInterceptor
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import feign.slf4j.Slf4jLogger
import teamcity.interactor.client.ClientFactory
import teamcity.interactor.config.ConfigReader

private data class TeamCityServerConfig(val username: String, val password: String, val baseUrl: String)

private val TEAM_CITY_SERVER_CONFIG = ConfigReader().config("teamcity-server-config.json", TeamCityServerConfig::class.java)
val TEAM_CITY_CLIENT_FACTORY = object : ClientFactory<TeamCityClient> {
    override fun client() =
            Feign.builder()
                    .encoder(JacksonEncoder(XmlMapper().registerModule(KotlinModule())))
                    .decoder(JacksonDecoder(XmlMapper().registerModule(KotlinModule()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                    .logger(Slf4jLogger(TeamCityClient::class.java))
                    .logLevel(Logger.Level.FULL)
                    .requestInterceptor(BasicAuthRequestInterceptor(TEAM_CITY_SERVER_CONFIG.username, TEAM_CITY_SERVER_CONFIG.password))
                    .target(TeamCityClient::class.java, TEAM_CITY_SERVER_CONFIG.baseUrl)
}

interface TeamCityClient {
    @RequestLine("POST /buildQueue")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml")
    fun build(request: TeamCityBuildRequest): TeamCityBuild

    @RequestLine("POST /{path}/id:{id}")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml")
    fun cancel(@Param("path") path: String, @Param("id") id: String, request: TeamCityCancelRequest = TeamCityCancelRequest())

    @RequestLine("GET /buildQueue/id:{id}")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml")
    fun status(@Param("id") id: String): TeamCityBuild
}

@JacksonXmlRootElement(localName = "build")
data class TeamCityBuild(val buildType: TeamCityBuildType, val id: String, val number: String?, val state: String, val status: String?)

@JacksonXmlRootElement(localName = "build")
data class TeamCityBuildRequest(val buildType: TeamCityBuildType)

@JacksonXmlRootElement(localName = "buildType")
data class TeamCityBuildType(@JacksonXmlProperty(isAttribute = true) val id: String)

@JacksonXmlRootElement(localName = "buildCancelRequest")
data class TeamCityCancelRequest(val comment: String = "Build cancelled by the user", val readIntoQueue: Boolean = true)