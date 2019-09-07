package teamcity.interactor.app

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import feign.Headers
import feign.RequestLine

interface BuildServerClient {
    @RequestLine("GET")
    @Headers("Accept: application/json",
            "Content-Type: application/json")
    fun getBuilds(): List<BuildName>

    @RequestLine("DELETE")
    @Headers("Content-Type: application/json")
    fun deleteBuild(build: BuildName)
}

@JacksonXmlRootElement
data class BuildName(val id: String)
