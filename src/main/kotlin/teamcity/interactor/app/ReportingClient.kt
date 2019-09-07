package teamcity.interactor.app

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import feign.Headers
import feign.RequestLine

interface ReportingClient {
    @RequestLine("POST")
    @Headers("Content-Type: application/json")
    fun report(text: Report)
}

@JacksonXmlRootElement
data class Report(val text: String)
