package teamcity.interactor.app

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests
import net.minidev.json.JSONArray.toJSONString
import net.minidev.json.JSONObject.toJSONString
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import teamcity.interactor.app.Application.BuildInformation
import teamcity.interactor.client.teamcity.TeamCityBuild
import teamcity.interactor.client.teamcity.TeamCityBuildType
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.random.Random

class CancelBuildsTest {
    private val teamCityUserName = "username-${Random.nextInt()}"
    private val teamCityPassword = "password-${Random.nextInt()}"
    private val buildServer = WireMockServer(wireMockConfig().dynamicPort())
    private val teamCityServer = WireMockServer(wireMockConfig().dynamicPort())
    private val slackServer = WireMockServer(wireMockConfig().dynamicPort())

    @BeforeEach
    fun setUp() {
        buildServer.start()
        teamCityServer.start()
        slackServer.start()
    }

    @AfterEach
    fun tearDown() {
        buildServer.stop()
        teamCityServer.stop()
        slackServer.stop()
    }

    data class TestConfiguration(val buildConfigList: List<BuildConfig>,
                                 val buildServerCancelRequestList: List<BuildServerCancelRequest>,
                                 val teamCityServerBuildList: List<TeamCityServerBuild>,
                                 val buildInformationList: List<BuildInformation>)

    data class ReportingMessage(val text: String, val imageUrl: String, val altText: String)
    data class BuildServerCancelRequest(val name: String, val responseUrl: String)
    data class TeamCityServerBuild(val name: String, val id: String)

    companion object {
        @JvmStatic
        fun cancelBuildsSuccessfullyTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of(TestConfiguration(
                                listOf(BuildConfig("teamCityBuildName", setOf("buildServerBuildName"))),
                                listOf(BuildServerCancelRequest("buildServerBuildName", "responseUrl")),
                                listOf(TeamCityServerBuild("teamCityBuildName", "teamCityBuildId")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName"), "teamCityBuildId", null, "none", null), "responseUrl")))),

                        Arguments.of(TestConfiguration(
                                listOf(BuildConfig("teamCityBuildName1", setOf("buildServerBuildName1")), BuildConfig("teamCityBuildName2", setOf("buildServerBuildName2"))),
                                listOf(BuildServerCancelRequest("buildServerBuildName1", "responseUrl1"), BuildServerCancelRequest("buildServerBuildName2", "responseUrl2")),
                                listOf(TeamCityServerBuild("teamCityBuildName1", "teamCityBuildId1"), TeamCityServerBuild("teamCityBuildName2", "teamCityBuildId2")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId1", null, "none", null), "responseUrl1"),
                                        BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName2"), "teamCityBuildId2", null, "none", null), "responseUrl2")))))
    }

    @Test
    fun doNotCancelAnyTeamCityBuildWhenThereIsNoSubmittedCancelRequest() {
        val underTest = Application(
                buildConfigs = listOf(BuildConfig("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}"))),
                jobConfigs = listOf(JobConfig("cancelBuilds", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))

        givenBuildServerReturnsCancelRequests(emptyList())

        underTest.run()

        // TODO Remove sleep
        TimeUnit.SECONDS.sleep(1)

        buildServer.verify(getRequestedFor(urlEqualTo("/cancel"))
                .withHeader("Accept", equalTo("application/json")))

        teamCityServer.verify(0, allRequests())

        assertThat(underTest.getBuilds(), empty())
    }

    @Test
    fun reportBuildNotFoundWhenSubmittedCancelRequestCannotBeMappedToAnExistingBuild() {
        val underTest = Application(
                buildConfigs = listOf(BuildConfig("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}"))),
                jobConfigs = listOf(JobConfig("cancelBuilds", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage("No queued/running buildName build is found", "https://cdn3.iconfinder.com/data/icons/network-and-communications-8/32/network_Error_lost_no_page_not_found-512.png", "Not Found")
        val buildInformationList = listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName"), "teamCityBuildId", null, "running", null), "responseUrl"))
        underTest.setBuilds { buildInformationList }

        givenBuildServerReturnsCancelRequests(listOf(BuildServerCancelRequest("buildName", "${slackServer.baseUrl()}/responseUrl")))
        givenBuildServerDeletesCancelRequestsSuccessfully(listOf("buildName"))
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))

        underTest.run()

        // TODO Remove sleep
        TimeUnit.SECONDS.sleep(1)

        buildServer.verify(getRequestedFor(urlEqualTo("/cancel"))
                .withHeader("Accept", equalTo("application/json")))

        verifyBuildServerDeleteCancelRequestsIsCalled(listOf("buildName"))
        verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))

        assertThat(underTest.getBuilds(), contains(*buildInformationList.toTypedArray()))
    }

    // TODO Use better display names for parameterized test rows
    @ParameterizedTest
    @MethodSource("cancelBuildsSuccessfullyTestCases")
    fun cancelBuilds(testConfig: TestConfiguration) {
        val underTest = Application(
                buildConfigs = testConfig.buildConfigList,
                jobConfigs = listOf(JobConfig("cancelBuilds", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        underTest.setBuilds { testConfig.buildInformationList }

        givenBuildServerReturnsCancelRequests(testConfig.buildServerCancelRequestList)
        givenTeamCityServerCancelsBuildsSuccessfully(testConfig.teamCityServerBuildList.map { it.id })
        givenBuildServerDeletesCancelRequestsSuccessfully(testConfig.buildServerCancelRequestList.map { it.name })

        underTest.run()

        // TODO Remove sleep
        TimeUnit.SECONDS.sleep(1)

        verifyBuildServerGetCancelRequestsIsCalled()
        verifyTeamCityServerCancelRequestsIsCalledFor(testConfig.teamCityServerBuildList.map { it.id })
        verifyBuildServerDeleteCancelRequestsIsCalled(testConfig.buildServerCancelRequestList.map { it.name })

        assertThat(underTest.getBuilds(), contains(*testConfig.buildInformationList.toTypedArray()))
    }

    private fun givenBuildServerReturnsCancelRequests(cancelRequests: List<BuildServerCancelRequest>) =
            buildServer.stubFor(get(urlEqualTo("/cancel"))
                    .withHeader("Accept", equalTo("application/json"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(toJSONString(
                                    cancelRequests.map {
                                        mapOf(
                                                "id" to it.name,
                                                "responseUrl" to it.responseUrl)
                                    }.toList()))))

    private fun givenSlackServerAcceptsReportingMessages(responseUrl: String, reportingMessages: List<ReportingMessage>) =
            slackServer.stubFor(post(urlEqualTo(responseUrl))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(toJSONString(mapOf(
                                    "blocks" to reportingMessages
                                            .map {
                                                mapOf(
                                                        "type" to "section",
                                                        "text" to mapOf(
                                                                "type" to "mrkdwn",
                                                                "text" to it.text),
                                                        "accessory" to mapOf(
                                                                "type" to "image",
                                                                "image_url" to it.imageUrl,
                                                                "alt_text" to it.altText)
                                                )
                                            })))))


    private fun givenBuildServerDeletesCancelRequestsSuccessfully(buildServerBuildNames: List<String>) =
            buildServerBuildNames
                    .forEach {
                        buildServer.stubFor(delete(urlEqualTo("/cancel"))
                                .withHeader("Content-Type", equalTo("application/json"))
                                .withRequestBody(equalToJson(toJSONString(mapOf("id" to it))))
                                .willReturn(aResponse()
                                        .withStatus(200)))
                    }

    // TODO Find xml builder
    private fun givenTeamCityServerCancelsBuildsSuccessfully(teamCityBuildIds: List<String>) =
            teamCityBuildIds
                    .forEach {
                        teamCityServer.stubFor(post(urlEqualTo("/buildQueue/id:$it"))
                                .withHeader("Accept", equalTo("application/xml"))
                                .withHeader("Content-Type", equalTo("application/xml"))
                                .withBasicAuth(teamCityUserName, teamCityPassword)
                                .withRequestBody(equalToXml("<buildCancelRequest><comment>Build cancelled by the user</comment><readIntoQueue>true</readIntoQueue></buildCancelRequest>"))
                                .willReturn(aResponse()
                                        .withStatus(200)))
                    }

    private fun verifyBuildServerGetCancelRequestsIsCalled() =
            buildServer.verify(getRequestedFor(urlEqualTo("/cancel"))
                    .withHeader("Accept", equalTo("application/json")))

    // TODO Find xml builder
    private fun verifyTeamCityServerCancelRequestsIsCalledFor(teamCityBuildIds: List<String>) =
            teamCityBuildIds
                    .forEach {
                        teamCityServer.verify(postRequestedFor(urlEqualTo("/buildQueue/id:$it"))
                                .withHeader("Accept", equalTo("application/xml"))
                                .withHeader("Content-Type", equalTo("application/xml"))
                                .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword))
                                .withRequestBody(equalToXml("<buildCancelRequest><comment>Build cancelled by the user</comment><readIntoQueue>true</readIntoQueue></buildCancelRequest>")))
                    }

    private fun verifyBuildServerDeleteCancelRequestsIsCalled(teamCityBuildNames: List<String>) =
            teamCityBuildNames
                    .forEach {
                        buildServer.verify(deleteRequestedFor(urlEqualTo("/cancel"))
                                .withHeader("Content-Type", equalTo("application/json"))
                                .withRequestBody(equalToJson(toJSONString(mapOf("id" to it)))))
                    }

    private fun verifySlackServerReportMessagesIsCalled(responseUrl: String, reportingMessages: List<ReportingMessage>) =
            slackServer.verify(postRequestedFor(urlEqualTo(responseUrl))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson(toJSONString(mapOf(
                            "blocks" to reportingMessages
                                    .map {
                                        mapOf(
                                                "type" to "section",
                                                "text" to mapOf(
                                                        "type" to "mrkdwn",
                                                        "text" to it.text),
                                                "accessory" to mapOf(
                                                        "type" to "image",
                                                        "image_url" to it.imageUrl,
                                                        "alt_text" to it.altText)
                                        )
                                    })))))
}