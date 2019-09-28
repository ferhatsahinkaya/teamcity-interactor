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

fun getTeamCityClient(baseUrl: String, username: String, password: String): TeamCityClient =
        Feign.builder()
                .encoder(JacksonEncoder(XmlMapper().registerModule(KotlinModule())))
                .decoder(JacksonDecoder(XmlMapper().registerModule(KotlinModule()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .logger(Slf4jLogger(TeamCityClient::class.java))
                .logLevel(Logger.Level.FULL)
                .requestInterceptor(BasicAuthRequestInterceptor(username, password))
                .target(TeamCityClient::class.java, baseUrl)

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

    @RequestLine("GET /builds/buildType:{id}")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml")
    fun state(@Param("id") buildTypeId: String): TeamCityBuild

    @RequestLine("GET /projects/id:{id}")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml")
    fun project(@Param("id") projectId: String): TeamCityProject
}

@JacksonXmlRootElement(localName = "project")
data class TeamCityProject(val buildTypes: List<TeamCityBuildType>, val projects: List<ProjectType>?) // TODO Can we use empty set instead of null

@JacksonXmlRootElement(localName = "build")
data class TeamCityBuild(val buildType: TeamCityBuildType, val id: String, val number: String?, val state: String, val status: String?)

@JacksonXmlRootElement(localName = "build")
data class TeamCityBuildRequest(val buildType: TeamCityBuildType)

@JacksonXmlRootElement(localName = "buildType")
data class TeamCityBuildType(@JacksonXmlProperty(isAttribute = true) val id: String, @JacksonXmlProperty(isAttribute = true) val name: String? = null)

@JacksonXmlRootElement(localName = "project")
data class ProjectType(@JacksonXmlProperty(isAttribute = true) val id: String)

@JacksonXmlRootElement(localName = "buildCancelRequest")
data class TeamCityCancelRequest(val comment: String = "Build cancelled by the user", val readIntoQueue: Boolean = true)