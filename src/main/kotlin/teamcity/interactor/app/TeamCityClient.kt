package teamcity.interactor.app

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import feign.Headers
import feign.Param
import feign.RequestLine

// TODO remove authorization value
interface TeamCityClient {
    @RequestLine("POST")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml",
            "Authorization: Basic dGVhbS5oaXJvOnBhc3N3b3Jk")
    fun build(build: TeamCityBuild): TeamCityBuild

    @RequestLine("GET /id:{id}")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml",
            "Authorization: Basic dGVhbS5oaXJvOnBhc3N3b3Jk")
    fun status(@Param("id") id: String): TeamCityBuild
}

@JacksonXmlRootElement(localName = "build")
data class TeamCityBuild(val buildType: TeamCityBuildType, val id: String? = null, val state: String? = null, val status: String? = null)

@JacksonXmlRootElement(localName = "buildType")
data class TeamCityBuildType(@JacksonXmlProperty(isAttribute = true) val id: String)