package teamcity.interactor.app

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests
import net.minidev.json.JSONArray
import net.minidev.json.JSONObject.toJSONString
import org.awaitility.Awaitility.await
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit.SECONDS
import java.util.stream.Stream
import kotlin.random.Random

class WatchStateTest {
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

    data class StateRequest(val groupId: String, val responseUrl: String)
    data class ReportingMessage(val text: String, val imageUrl: String, val altText: String)

    @Test
    fun doNotCheckStatusWhenThereIsNoStateRequestsToWatch() {
        val underTest = Application(
                buildConfig = BuildConfig(listOf(Group(setOf("Provisioning"), setOf("teamCityBuildName-${Random.nextInt()}"))), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = listOf(JobConfig("watchState", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        givenBuildServerReturnsStateRequests(listOf())

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    teamCityServer.verify(0, allRequests())
                    slackServer.verify(0, allRequests())
                }
    }

    companion object {
        @JvmStatic
        fun allSuccessfulBuildsTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of("groupId1", emptySet<String>(), emptySet<String>()),
                        Arguments.of("groupId2", setOf("buildId1"), emptySet<String>()),
                        Arguments.of("groupId3", setOf("buildId1"), setOf("buildId2")),
                        Arguments.of("groupId4", setOf("buildId1", "buildId2"), setOf("buildId3", "buildId4", "buildId5")),
                        Arguments.of("groupId5", setOf("buildId1", "buildId2", "buildId3", "buildId4"), setOf("buildId5")))

        @JvmStatic
        fun someFailedBuildsTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of("groupId1", emptySet<String>(), setOf("buildId1"), setOf("buildId2", "buildId3")),
                        Arguments.of("groupId2", setOf("buildId1"), setOf("buildId2"), emptySet<String>()),
                        Arguments.of("groupId3", setOf("buildId1", "buildId2"), setOf("buildId3"), setOf("buildId4")),
                        Arguments.of("groupId4", setOf("buildId1", "buildId2"), setOf("buildId3"), setOf("buildId4", "buildId5", "buildId6")),
                        Arguments.of("groupId5", setOf("buildId1", "buildId2", "buildId3", "buildId4"), setOf("buildId5"), setOf("buildId6")),
                        Arguments.of("groupId6", emptySet<String>(), setOf("buildId1", "buildId2", "buildId3", "buildId4"), emptySet<String>()))
    }

    @ParameterizedTest
    @MethodSource("allSuccessfulBuildsTestCases")
    fun watchStateWhenAllBuildsSuccessful(groupId: String, buildIds: Set<String>, nonExistingBuildIds: Set<String>) {
        val underTest = Application(
                buildConfig = BuildConfig(listOf(Group(setOf(groupId), buildIds.plus(nonExistingBuildIds))), emptyList()),
                jobConfigs = listOf(JobConfig("watchState", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage("All *${groupId}* builds are successful!", "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png", "Success")

        givenBuildServerReturnsStateRequests(listOf(StateRequest(groupId, "${slackServer.baseUrl()}/responseUrl")))
        givenTeamCityServerReturnsStateSuccessfully(buildIds, "SUCCESS")
        givenTeamCityServerReturnsStateNotExisting(nonExistingBuildIds)
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))
        givenBuildServerDeletesStateRequestsSuccessfully(groupId)

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    verifyTeamCityServerGetStateIsCalledFor(buildIds.plus(nonExistingBuildIds))
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    verifyBuildServerDeleteStateRequestsIsCalled(groupId)

                    assertThat(underTest.getBuilds(), empty())
                }
    }

    @ParameterizedTest
    @MethodSource("someFailedBuildsTestCases")
    fun watchStateWhenSomeBuildsFailed(groupId: String, successfulBuildIds: Set<String>, failedBuildsIds: Set<String>, nonExistingBuildIds: Set<String>) {
        val underTest = Application(
                buildConfig = BuildConfig(listOf(Group(setOf(groupId), successfulBuildIds.plus(failedBuildsIds).plus(nonExistingBuildIds))), emptyList()),
                jobConfigs = listOf(JobConfig("watchState", 0, Long.MAX_VALUE)),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage(failedBuildsIds.joinToString(prefix = "Following *$groupId* builds are currently failing:\n", separator = "\n") { "*$it*" }, "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png", "Failure")

        givenBuildServerReturnsStateRequests(listOf(StateRequest(groupId, "${slackServer.baseUrl()}/responseUrl")))
        givenTeamCityServerReturnsStateSuccessfully(successfulBuildIds, "SUCCESS")
        givenTeamCityServerReturnsStateSuccessfully(failedBuildsIds, "FAILURE")
        givenTeamCityServerReturnsStateNotExisting(nonExistingBuildIds)
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))
        givenBuildServerDeletesStateRequestsSuccessfully(groupId)

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    verifyTeamCityServerGetStateIsCalledFor(successfulBuildIds.plus(failedBuildsIds).plus(nonExistingBuildIds))
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    verifyBuildServerDeleteStateRequestsIsCalled(groupId)

                    assertThat(underTest.getBuilds(), empty())
                }
    }

    private fun givenBuildServerReturnsStateRequests(stateRequests: List<StateRequest>) =
            buildServer.stubFor(get(urlEqualTo("/state"))
                    .withHeader("Accept", equalTo("application/json"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(JSONArray.toJSONString(
                                    stateRequests.map {
                                        mapOf(
                                                "id" to it.groupId,
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

    // TODO Find xml builder
    private fun givenTeamCityServerReturnsStateSuccessfully(buildIds: Set<String>, status: String) =
            buildIds.forEach {
                teamCityServer.stubFor(get(urlEqualTo("/builds/buildType:$it"))
                        .withHeader("Accept", equalTo("application/xml"))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withBasicAuth(teamCityUserName, teamCityPassword)
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody("<build><buildType id=\"${"buildId-" + Random.nextInt()}\" name=\"$it\"></buildType><id>teamCityBuildId</id><state>finished</state><number>${Random.nextInt()}</number><status>$status</status></build>")))
            }

    private fun givenTeamCityServerReturnsStateNotExisting(buildIds: Set<String>) =
            buildIds.forEach {
                teamCityServer.stubFor(get(urlEqualTo("/builds/buildType:$it"))
                        .withHeader("Accept", equalTo("application/xml"))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withBasicAuth(teamCityUserName, teamCityPassword)
                        .willReturn(aResponse().withStatus(404)))
            }

    private fun givenBuildServerDeletesStateRequestsSuccessfully(groupId: String) =
            buildServer.stubFor(delete(urlEqualTo("/state"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson(toJSONString(mapOf("id" to groupId))))
                    .willReturn(aResponse()
                            .withStatus(200)))

    private fun verifyBuildServerStateRequestsIsCalled() =
            buildServer.verify(getRequestedFor(urlEqualTo("/state"))
                    .withHeader("Accept", equalTo("application/json")))

    private fun verifyTeamCityServerGetStateIsCalledFor(buildIds: Set<String>) =
            buildIds.forEach {
                teamCityServer.verify(1, getRequestedFor(urlEqualTo("/builds/buildType:$it"))
                        .withHeader("Accept", equalTo("application/xml"))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword)))
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

    private fun verifyBuildServerDeleteStateRequestsIsCalled(groupId: String) =
            buildServer.verify(1, deleteRequestedFor(urlEqualTo("/state"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson(toJSONString(mapOf("id" to groupId)))))
}