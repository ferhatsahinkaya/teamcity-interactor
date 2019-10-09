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
import teamcity.interactor.app.Application.BuildInformation
import teamcity.interactor.client.teamcity.TeamCityBuild
import teamcity.interactor.client.teamcity.TeamCityBuildType
import java.util.concurrent.TimeUnit.SECONDS
import java.util.stream.Stream
import kotlin.random.Random

class TriggerBuildsTest {
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
                                 val buildServerBuildList: List<BuildServerBuild>,
                                 val teamCityServerBuildList: List<TeamCityServerBuild>,
                                 val buildInformationList: List<BuildInformation>)

    data class ReportingMessage(val text: String, val imageUrl: String, val altText: String)
    data class BuildServerBuild(val name: String, val responseUrl: String)
    data class TeamCityServerBuild(val name: String, val id: String)

    companion object {
        @JvmStatic
        fun triggerBuildsSuccessfullyTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of(TestConfiguration(
                                BuildConfig(emptyList(), listOf(Build("teamCityBuildName", setOf("buildServerBuildName")))),
                                listOf(BuildServerBuild("buildServerBuildName", "responseUrl")),
                                listOf(TeamCityServerBuild("teamCityBuildName", "teamCityBuildId")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName"), "teamCityBuildId", null, "none", null), "responseUrl")))),

                        Arguments.of(TestConfiguration(
                                BuildConfig(emptyList(), listOf(Build("teamCityBuildName1", setOf("buildServerBuildName1")), Build("teamCityBuildName2", setOf("buildServerBuildName2")))),
                                listOf(BuildServerBuild("buildServerBuildName1", "responseUrl1"), BuildServerBuild("buildServerBuildName2", "responseUrl2")),
                                listOf(TeamCityServerBuild("teamCityBuildName1", "teamCityBuildId1"), TeamCityServerBuild("teamCityBuildName2", "teamCityBuildId2")),
                                listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName1"), "teamCityBuildId1", null, "none", null), "responseUrl1"),
                                        BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName2"), "teamCityBuildId2", null, "none", null), "responseUrl2")))))
    }

    @Test
    fun doNotTriggerAnyTeamCityBuildWhenThereIsNoSubmittedBuildRequest() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = JobConfig(listOf(Job("triggerBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))

        givenBuildServerReturnsBuilds(emptyList())

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetBuildsIsCalled()

                    teamCityServer.verify(0, allRequests())

                    assertThat(underTest.getBuilds(), empty())
                }
    }

    @Test
    fun reportBuildNotFoundWhenSubmittedBuildRequestCannotBeMappedToABuildConfiguration() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = JobConfig(listOf(Job("triggerBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage("buildName build is not found", "https://cdn3.iconfinder.com/data/icons/network-and-communications-8/32/network_Error_lost_no_page_not_found-512.png", "Not Found")

        givenBuildServerReturnsBuilds(listOf(BuildServerBuild("buildName", "${slackServer.baseUrl()}/responseUrl")))
        givenBuildServerDeletesBuildsSuccessfully(listOf("buildName"))
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetBuildsIsCalled()
                    verifyBuildServerDeleteBuildsIsCalled(listOf("buildName"))
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))

                    assertThat(underTest.getBuilds(), empty())
                }
    }

    @ParameterizedTest
    @MethodSource("triggerBuildsSuccessfullyTestCases")
    fun triggerBuilds(testConfig: TestConfiguration) {
        val underTest = Application(
                buildConfig = testConfig.buildConfig,
                jobConfigs = JobConfig(listOf(Job("triggerBuilds", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))

        givenBuildServerReturnsBuilds(testConfig.buildServerBuildList)
        givenBuildServerDeletesBuildsSuccessfully(testConfig.buildServerBuildList.map { it.name })
        givenTeamCityServerCreatesBuildsSuccessfully(testConfig.teamCityServerBuildList)

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerGetBuildsIsCalled()
                    verifyTeamCityServerBuildIsCalledFor(testConfig.teamCityServerBuildList.map { it.name })
                    verifyBuildServerDeleteBuildsIsCalled(testConfig.buildServerBuildList.map { it.name })

                    assertThat(underTest.getBuilds(), contains(*testConfig.buildInformationList.toTypedArray()))
                }
    }

    private fun givenBuildServerReturnsBuilds(builds: List<BuildServerBuild>) =
            buildServer.stubFor(get(urlEqualTo("/build"))
                    .withHeader("Accept", equalTo("application/json"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(toJSONString(
                                    builds.map {
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


    private fun givenBuildServerDeletesBuildsSuccessfully(buildServerBuildNames: List<String>) =
            buildServerBuildNames
                    .forEach {
                        buildServer.stubFor(delete(urlEqualTo("/build"))
                                .withHeader("Content-Type", equalTo("application/json"))
                                .withRequestBody(equalToJson(toJSONString(mapOf("id" to it))))
                                .willReturn(aResponse()
                                        .withStatus(200)))
                    }

    private fun givenTeamCityServerCreatesBuildsSuccessfully(teamCityBuilds: List<TeamCityServerBuild>) =
            teamCityBuilds
                    .forEach {
                        teamCityServer.stubFor(post(urlEqualTo("/buildQueue"))
                                .withHeader("Accept", equalTo("application/xml"))
                                .withHeader("Content-Type", equalTo("application/xml"))
                                .withBasicAuth(teamCityUserName, teamCityPassword)
                                .withRequestBody(equalToXml("<build><buildType id=\"${it.name}\"/></build>"))
                                .willReturn(aResponse()
                                        .withStatus(200)
                                        .withBody("<build><buildType id=\"${it.name}\"></buildType><id>${it.id}</id><state>some-state-${Random.nextInt(100)}</state></build>")))
                    }

    private fun verifyBuildServerGetBuildsIsCalled() =
            buildServer.verify(getRequestedFor(urlEqualTo("/build"))
                    .withHeader("Accept", equalTo("application/json")))

    private fun verifyTeamCityServerBuildIsCalledFor(teamCityBuildNames: List<String>) =
            teamCityBuildNames
                    .forEach {
                        teamCityServer.verify(postRequestedFor(urlEqualTo("/buildQueue"))
                                .withHeader("Accept", equalTo("application/xml"))
                                .withHeader("Content-Type", equalTo("application/xml"))
                                .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword))
                                .withRequestBody(equalToXml("<build><buildType id=\"$it\"/></build>")))
                    }

    private fun verifyBuildServerDeleteBuildsIsCalled(teamCityBuildNames: List<String>) =
            teamCityBuildNames
                    .forEach {
                        buildServer.verify(deleteRequestedFor(urlEqualTo("/build"))
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