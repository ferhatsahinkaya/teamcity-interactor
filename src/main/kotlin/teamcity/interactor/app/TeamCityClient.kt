package teamcity.interactor.app

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import feign.Headers
import feign.RequestLine

interface TeamCityClient {
    @RequestLine("POST")
    @Headers("Accept: application/xml",
            "Content-Type: application/xml",
            "Authorization: Basic dGVhbS5oaXJvOnBhc3N3b3Jk")
    fun build(build: TeamCityBuild)
}

@JacksonXmlRootElement(localName = "build")
data class TeamCityBuild(val buildType: TeamCityBuildType)

@JacksonXmlRootElement(localName = "buildType")
data class TeamCityBuildType(@JacksonXmlProperty(isAttribute = true) val id: String)