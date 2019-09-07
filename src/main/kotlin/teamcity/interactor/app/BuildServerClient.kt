package teamcity.interactor.app

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import feign.Headers
import feign.RequestLine

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
