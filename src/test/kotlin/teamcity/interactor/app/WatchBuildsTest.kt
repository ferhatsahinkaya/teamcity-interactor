package teamcity.interactor.app

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests
import net.minidev.json.JSONObject.toJSONString
import org.awaitility.Awaitility.await
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import teamcity.interactor.app.Application.BuildInformation
import teamcity.interactor.client.teamcity.TeamCityBuild
import teamcity.interactor.client.teamcity.TeamCityBuildType
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.random.Random

class WatchBuildsTest {
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

    data class ReportingMessage(val text: String, val imageUrl: String, val altText: String)

    @Test
    fun doNotCheckStatusWhenThereIsNoBuildInformationToWatch() {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = listOf(JobConfig("watchBuilds", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    buildServer.verify(0, allRequests())
                    teamCityServer.verify(0, allRequests())
                    slackServer.verify(0, allRequests())

                    assertThat(underTest.getBuilds(), empty())
                }
    }

    // TODO Use better display names for parameterized test rows
    @ParameterizedTest
    @CsvSource(
            "none, queued, any-status, queued, https://cdn1.iconfinder.com/data/icons/company-business-people-1/32/busibess_people-40-512.png, Queued",
            "none, running, running, running, https://cdn3.iconfinder.com/data/icons/living/24/254_running_activity_fitness-512.png, Running",
            "queued, running, running, running, https://cdn3.iconfinder.com/data/icons/living/24/254_running_activity_fitness-512.png, Running")
    fun watchActiveBuilds(initialState: String, finalState: String, finalStatus: String, displayName: String, imageUrl: String, altText: String) {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), emptyList()),
                jobConfigs = listOf(JobConfig("watchBuilds", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        underTest.setBuilds { listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName", "TeamCityDisplayName"), "teamCityBuildId", null, initialState, null), "${slackServer.baseUrl()}/responseUrl")) }
        val buildNumber = "number-${Random.nextInt()}"
        val reportingMessage = ReportingMessage("*TeamCityBuildDisplayName* build *$buildNumber* is $displayName", imageUrl, altText)

        givenTeamCityServerReturnsStatusSuccessfully(TeamCityBuild(TeamCityBuildType("teamCityBuildName", "TeamCityBuildDisplayName"), "teamCityBuildId", buildNumber, finalState, finalStatus))
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))

        underTest.run()

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    buildServer.verify(0, allRequests())
                    verifyTeamCityServerGetStatusesIsCalledFor("teamCityBuildId")
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    assertThat(underTest.getBuilds(), contains(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName", "TeamCityBuildDisplayName"), "teamCityBuildId", buildNumber, finalState, finalStatus), "${slackServer.baseUrl()}/responseUrl")))
                }
    }

    // TODO Use better display names for parameterized test rows
    @ParameterizedTest
    @CsvSource(
            "none, finished, SUCCESS, finished successfully, https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png, Success",
            "none, finished, FAILURE, failed, https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png, Failure",
            "none, finished, UNKNOWN, cancelled, https://cdn3.iconfinder.com/data/icons/cleaning-icons/512/Dumpster-512.png, Failure",
            "queued, finished, SUCCESS, finished successfully, https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png, Success",
            "queued, finished, FAILURE, failed, https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png, Failure",
            "queued, finished, UNKNOWN, cancelled, https://cdn3.iconfinder.com/data/icons/cleaning-icons/512/Dumpster-512.png, Failure",
            "running, finished, SUCCESS, finished successfully, https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png, Success",
            "running, finished, FAILURE, failed, https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png, Failure",
            "running, finished, UNKNOWN, cancelled, https://cdn3.iconfinder.com/data/icons/cleaning-icons/512/Dumpster-512.png, Failure")
    fun watchFinishedBuilds(initialState: String, finalState: String, finalStatus: String, displayName: String, imageUrl: String, altText: String) {
        val underTest = Application(
                buildConfig = BuildConfig(emptyList(), emptyList()),
                jobConfigs = listOf(JobConfig("watchBuilds", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        underTest.setBuilds { listOf(BuildInformation(TeamCityBuild(TeamCityBuildType("teamCityBuildName", "TeamCityBuildDisplayName"), "teamCityBuildId", null, initialState, null), "${slackServer.baseUrl()}/responseUrl")) }
        val buildNumber = "number-${Random.nextInt()}"
        val reportingMessage = ReportingMessage("*TeamCityBuildDisplayName* build *$buildNumber* is $displayName", imageUrl, altText)

        givenTeamCityServerReturnsStatusSuccessfully(TeamCityBuild(TeamCityBuildType("teamCityBuildName", "TeamCityBuildDisplayName"), "teamCityBuildId", buildNumber, finalState, finalStatus))
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))

        underTest.run()

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    buildServer.verify(0, allRequests())
                    verifyTeamCityServerGetStatusesIsCalledFor("teamCityBuildId")
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    assertThat(underTest.getBuilds(), empty())
                }
    }

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

    // TODO Find xml builder
    private fun givenTeamCityServerReturnsStatusSuccessfully(teamCityBuild: TeamCityBuild) =
            teamCityServer.stubFor(get(urlEqualTo("/buildQueue/id:${teamCityBuild.id}"))
                    .withHeader("Accept", equalTo("application/xml"))
                    .withHeader("Content-Type", equalTo("application/xml"))
                    .withBasicAuth(teamCityUserName, teamCityPassword)
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("<build><buildType id=\"${teamCityBuild.buildType.id}\" name=\"${teamCityBuild.buildType.name}\"></buildType><id>teamCityBuildId</id><state>${teamCityBuild.state}</state><number>${teamCityBuild.number}</number><status>${teamCityBuild.status}</status></build>")))

    private fun verifyTeamCityServerGetStatusesIsCalledFor(teamCityBuildId: String) =
            teamCityServer.verify(1, getRequestedFor(urlEqualTo("/buildQueue/id:$teamCityBuildId"))
                    .withHeader("Accept", equalTo("application/xml"))
                    .withHeader("Content-Type", equalTo("application/xml"))
                    .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword)))

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