package teamcity.interactor.app

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests
import net.minidev.json.JSONArray.toJSONString
import net.minidev.json.JSONObject.toJSONString
import org.awaitility.Awaitility.await
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import teamcity.interactor.app.Application.BuildInformation
import teamcity.interactor.client.teamcity.TeamCityBuild
import teamcity.interactor.client.teamcity.TeamCityBuildType
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
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

    data class TestConfiguration(val buildConfig: BuildConfig,
                                 val buildServerCancelRequestList: List<BuildServerCancelRequest>,
                                 val teamCityServerBuildList: List<TeamCityServerBuild>,
                                 val buildInformationList: List<BuildInformation>)

    data class ReportingMessage(val text: String, val imageUrl: String, val altText: String)
    data class BuildServerCancelRequest(val name: String, val responseUrl: String)
    data class TeamCityServerBuild(val name: String, val id: String, val resourcePath: String)
    data class TeamCityServerIdToResourcePath(val id: String, val resourcePath: String)

    companion object {
        @JvmStatic
        fun cancelBuildsSuccessfullyTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of(TestConfiguration(
                                BuildConfig(emptyList(), listOf(Build("teamCityBuildName", setOf("buildServerBuildName")))),
                                listOf(BuildServerCancelRequest("buildServerBuildName", "responseUrl")),
                                listOf(TeamCityServerBuild("teamCityBuildName", "teamCityBuildId", "buildQueue")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName"), "teamCityBuildId", null, "none", null), "responseUrl")))),

                        Arguments.of(TestConfiguration(
                                BuildConfig(emptyList(), listOf(Build("teamCityBuildName", setOf("buildServerBuildName")))),
                                listOf(BuildServerCancelRequest("buildServerBuildName", "responseUrl")),
                                listOf(TeamCityServerBuild("teamCityBuildName", "teamCityBuildId", "builds")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName"), "teamCityBuildId", null, "none", null), "responseUrl")))),

                        Arguments.of(TestConfiguration(
                                BuildConfig(emptyList(), listOf(Build("teamCityBuildName1", setOf("buildServerBuildName1")), Build("teamCityBuildName2", setOf("buildServerBuildName2")))),
                                listOf(BuildServerCancelRequest("buildServerBuildName1", "responseUrl1"), BuildServerCancelRequest("buildServerBuildName2", "responseUrl2")),
                                listOf(TeamCityServerBuild("teamCityBuildName1", "teamCityBuildId1", "buildQueue"), TeamCityServerBuild("teamCityBuildName2", "teamCityBuildId2", "buildQueue")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId1", null, "none", null), "responseUrl1"),
                                        BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName2"), "teamCityBuildId2", null, "none", null), "responseUrl2")))),

                        Arguments.of(TestConfiguration(
                                BuildConfig(emptyList(), listOf(Build("teamCityBuildName1", setOf("buildServerBuildName1")), Build("teamCityBuildName2", setOf("buildServerBuildName2")))),
                                listOf(BuildServerCancelRequest("buildServerBuildName1", "responseUrl1"), BuildServerCancelRequest("buildServerBuildName2", "responseUrl2")),
                                listOf(TeamCityServerBuild("teamCityBuildName1", "teamCityBuildId1", "buildQueue"), TeamCityServerBuild("teamCityBuildName2", "teamCityBuildId2", "builds")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId1", null, "none", null), "responseUrl1"),
                                        BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName2"), "teamCityBuildId2", null, "none", null), "responseUrl2")))))
    }

    @Test
    fun doNotCancelAnyTeamCityBuildWhenThereIsNoSubmittedCancelRequest() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = JobConfig(listOf(Job("cancelBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))

        givenBuildServerReturnsCancelRequests(emptyList())

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetCancelRequestsIsCalled()
                    teamCityServer.verify(0, allRequests())

                    assertThat(underTest.getBuilds(), empty())
                }
    }

    @Test
    fun reportBuildNotFoundWhenSubmittedCancelRequestCannotBeMappedToAnExistingBuild() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = JobConfig(listOf(Job("cancelBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage("No queued/running buildName build is found", "https://cdn3.iconfinder.com/data/icons/network-and-communications-8/32/network_Error_lost_no_page_not_found-512.png", "Not Found")
        val buildInformationList = listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName"), "teamCityBuildId", null, "running", null), "responseUrl"))
        underTest.setBuilds { buildInformationList }

        givenBuildServerReturnsCancelRequests(listOf(BuildServerCancelRequest("buildName", "${slackServer.baseUrl()}/responseUrl")))
        givenBuildServerDeletesCancelRequestsSuccessfully(listOf("buildName"))
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetCancelRequestsIsCalled()
                    verifyBuildServerDeleteCancelRequestsIsCalled(listOf("buildName"))
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))

                    assertThat(underTest.getBuilds(), contains(*buildInformationList.toTypedArray()))
                }
    }

    @ParameterizedTest
    @ValueSource(strings = ["builds", "buildQueue"])
    fun cancelBuildsWithAlternativeBuildServerName(resourcePath: String) {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName1", setOf("buildServerName1.1", "buildServerName1.2", "buildServerName1.3")), Build("teamCityBuildName2", setOf("buildServerName2")))),
                jobConfigs = JobConfig(listOf(Job("cancelBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val buildInformationList = listOf(
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId1", "teamCityBuildNumber1", "running", "SUCCESS"), "responseUrl1"),
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName2"), "teamCityBuildId2", "teamCityBuildNumber2", "running", "SUCCESS"), "responseUrl2"),
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName3"), "teamCityBuildId3", "teamCityBuildNumber3", "queued", "SUCCESS"), "responseUrl3"))
        underTest.setBuilds { buildInformationList }

        givenBuildServerReturnsCancelRequests(listOf(BuildServerCancelRequest("buildServerName1.3", "${slackServer.baseUrl()}/responseUrl")))
        givenTeamCityServerCancelsBuildsSuccessfully(listOf(TeamCityServerIdToResourcePath("teamCityBuildId1", resourcePath)))
        givenBuildServerDeletesCancelRequestsSuccessfully(listOf("buildServerName1.3"))

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetCancelRequestsIsCalled()
                    verifyTeamCityServerCancelRequestsIsCalledFor(listOf(TeamCityServerIdToResourcePath("teamCityBuildId1", resourcePath)))
                    verifyBuildServerDeleteCancelRequestsIsCalled(listOf("buildServerName1.3"))

                    assertThat(underTest.getBuilds(), contains(*buildInformationList.toTypedArray()))
                }
    }

    @Test
    fun cancelBuildsWithIdOnceWhenThereAreMultipleCancelRequestsWithSameBuildServerName() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName1", setOf("buildServerName1")), Build("teamCityBuildName2", setOf("buildServerName2")))),
                jobConfigs = JobConfig(listOf(Job("cancelBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val buildInformationList = listOf(
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId1", "teamCityBuildNumber1", "running", null), "responseUrl1"),
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId2", "teamCityBuildNumber2", "queued", "SUCCESS"), "responseUrl2"))
        underTest.setBuilds { buildInformationList }

        givenBuildServerReturnsCancelRequests(listOf(
                BuildServerCancelRequest("buildServerName1", "${slackServer.baseUrl()}/responseUrl1"),
                BuildServerCancelRequest("buildServerName1", "${slackServer.baseUrl()}/responseUrl2")))
        givenTeamCityServerCancelsBuildsSuccessfully(listOf(
                TeamCityServerIdToResourcePath("teamCityBuildId1", "buildQueue"),
                TeamCityServerIdToResourcePath("teamCityBuildId2", "builds")))
        givenBuildServerDeletesCancelRequestsSuccessfully(listOf("buildServerName1"))

        underTest.run()

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verifyBuildServerGetCancelRequestsIsCalled()
                    verifyTeamCityServerCancelRequestsIsCalledFor(listOf(
                            TeamCityServerIdToResourcePath("teamCityBuildId1", "buildQueue"),
                            TeamCityServerIdToResourcePath("teamCityBuildId2", "builds")))
                    verifyBuildServerDeleteCancelRequestsIsCalled(listOf("buildServerName1"))

                    assertThat(underTest.getBuilds(), contains(*buildInformationList.toTypedArray()))
                }
    }

    @Test
    fun cancelBuildsOnceWhenThereAreCancelRequestsForAlternativeNamesOfSameTeamCityBuild() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName1.1", setOf("buildServerName1.1", "buildServerName1.2", "buildServerName1.3")), Build("teamCityBuildName2", setOf("buildServerName2")))),
                jobConfigs = JobConfig(listOf(Job("cancelBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val buildInformationList = listOf(
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1.1"), "teamCityBuildId1.1", "teamCityBuildNumber1.1", "running", "SUCCESS"), "responseUrl1.1"),
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1.1"), "teamCityBuildId1.2", "teamCityBuildNumber1.2", "queued", "SUCCESS"), "responseUrl1.2"),
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName2"), "teamCityBuildId2", "teamCityBuildNumber2", "running", "SUCCESS"), "responseUrl2"),
                BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName3"), "teamCityBuildId3", "teamCityBuildNumber3", "queued", "SUCCESS"), "responseUrl3"))
        underTest.setBuilds { buildInformationList }

        givenBuildServerReturnsCancelRequests(listOf(
                BuildServerCancelRequest("buildServerName1.1", "${slackServer.baseUrl()}/responseUrl"),
                BuildServerCancelRequest("buildServerName1.2", "${slackServer.baseUrl()}/responseUrl")))
        givenTeamCityServerCancelsBuildsSuccessfully(listOf(
                TeamCityServerIdToResourcePath("teamCityBuildId1.1", "builds"),
                TeamCityServerIdToResourcePath("teamCityBuildId1.2", "buildQueue")))
        givenBuildServerDeletesCancelRequestsSuccessfully(listOf("buildServerName1.1", "buildServerName1.2"))

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetCancelRequestsIsCalled()
                    verifyTeamCityServerCancelRequestsIsCalledFor(listOf(
                            TeamCityServerIdToResourcePath("teamCityBuildId1.1", "builds"),
                            TeamCityServerIdToResourcePath("teamCityBuildId1.2", "buildQueue")))
                    verifyBuildServerDeleteCancelRequestsIsCalled(listOf("buildServerName1.1", "buildServerName1.2"))

                    assertThat(underTest.getBuilds(), contains(*buildInformationList.toTypedArray()))
                }
    }

    @ParameterizedTest
    @MethodSource("cancelBuildsSuccessfullyTestCases")
    fun cancelBuilds(testConfig: TestConfiguration) {
        val underTest = Application(
                buildConfig = testConfig.buildConfig,
                jobConfigs = JobConfig(listOf(Job("cancelBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        underTest.setBuilds { testConfig.buildInformationList }

        givenBuildServerReturnsCancelRequests(testConfig.buildServerCancelRequestList)
        givenTeamCityServerCancelsBuildsSuccessfully(testConfig.teamCityServerBuildList.map { TeamCityServerIdToResourcePath(it.id, it.resourcePath) })
        givenBuildServerDeletesCancelRequestsSuccessfully(testConfig.buildServerCancelRequestList.map { it.name })

        underTest.run()

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verifyBuildServerGetCancelRequestsIsCalled()
                    verifyTeamCityServerCancelRequestsIsCalledFor(testConfig.teamCityServerBuildList.map { TeamCityServerIdToResourcePath(it.id, it.resourcePath) })
                    verifyBuildServerDeleteCancelRequestsIsCalled(testConfig.buildServerCancelRequestList.map { it.name })

                    assertThat(underTest.getBuilds(), contains(*testConfig.buildInformationList.toTypedArray()))
                }
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

    private fun givenTeamCityServerCancelsBuildsSuccessfully(teamCityServerIdToResourcePaths: List<TeamCityServerIdToResourcePath>) =
            teamCityServerIdToResourcePaths
                    .forEach {
                        teamCityServer.stubFor(post(urlEqualTo("/${it.resourcePath}/id:${it.id}"))
                                .withHeader("Accept", equalTo("application/xml"))
                                .withHeader("Content-Type", equalTo("application/xml"))
                                .withBasicAuth(teamCityUserName, teamCityPassword)
                                .withRequestBody(equalToXml("<buildCancelRequest><comment>Build cancelled by the user</comment><readIntoQueue>true</readIntoQueue></buildCancelRequest>"))
                                .willReturn(aResponse()
                                        .withStatus(200)))
                    }

    private fun verifyBuildServerGetCancelRequestsIsCalled() =
            buildServer.verify(1, getRequestedFor(urlEqualTo("/cancel"))
                    .withHeader("Accept", equalTo("application/json")))

    private fun verifyTeamCityServerCancelRequestsIsCalledFor(teamCityServerIdToResourcePaths: List<TeamCityServerIdToResourcePath>) =
            teamCityServerIdToResourcePaths
                    .forEach {
                        teamCityServer.verify(1, postRequestedFor(urlEqualTo("/${it.resourcePath}/id:${it.id}"))
                                .withHeader("Accept", equalTo("application/xml"))
                                .withHeader("Content-Type", equalTo("application/xml"))
                                .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword))
                                .withRequestBody(equalToXml("<buildCancelRequest><comment>Build cancelled by the user</comment><readIntoQueue>true</readIntoQueue></buildCancelRequest>")))
                    }

    private fun verifyBuildServerDeleteCancelRequestsIsCalled(teamCityBuildNames: List<String>) =
            teamCityBuildNames
                    .forEach {
                        buildServer.verify(1, deleteRequestedFor(urlEqualTo("/cancel"))
                                .withHeader("Content-Type", equalTo("application/json"))
                                .withRequestBody(equalToJson(toJSONString(mapOf("id" to it)))))
                    }

    private fun verifySlackServerReportMessagesIsCalled(responseUrl: String, reportingMessages: List<ReportingMessage>) =
            slackServer.verify(1, postRequestedFor(urlEqualTo(responseUrl))
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