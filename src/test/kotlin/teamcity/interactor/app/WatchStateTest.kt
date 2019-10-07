package teamcity.interactor.app

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests
import net.minidev.json.JSONArray
import net.minidev.json.JSONObject.toJSONString
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit.SECONDS
import java.util.stream.Stream
import kotlin.random.Random

// TODO Test multiple state requests exist
// TODO Test multiple groupIds matching the given name, use the first group

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

    data class Project(val id: String, val subProjects: List<Project>, val builds: List<Build>)
    data class Build(val id: String, val status: String)

    @Test
    fun doNotCheckStatusWhenThereIsNoStateRequestsToWatch() {
        val underTest = Application(
                buildConfig = BuildConfig(listOf(Group(setOf("Provisioning"), listOf(Project("projectId-${Random.nextInt()}")))), listOf(Build("teamCityBuildName-${Random.nextInt()}", setOf("buildServerBuildName-${Random.nextInt()}")))),
                jobConfigs = JobConfig(listOf(Job("watchState", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))

        givenBuildServerReturnsStateRequests(emptyList())

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    teamCityServer.verify(0, allRequests())
                    slackServer.verify(0, allRequests())
                }
    }

    @ParameterizedTest
    @MethodSource("groupNotFoundTestCases")
    fun reportGroupNotFoundWhenSubmittedStateRequestCannotBeMappedToAGroupConfiguration(buildConfig: BuildConfig,
                                                                                        projects: List<Project>,
                                                                                        groupId: String,
                                                                                        calledProjectIds: Set<String>,
                                                                                        notCalledProjectIds: Set<String>) {
        val underTest = Application(
                buildConfig = buildConfig,
                jobConfigs = JobConfig(listOf(Job("watchState", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage("*$groupId* group is not found", "https://cdn3.iconfinder.com/data/icons/network-and-communications-8/32/network_Error_lost_no_page_not_found-512.png", "Not Found")

        givenBuildServerReturnsStateRequests(listOf(StateRequest(groupId, "${slackServer.baseUrl()}/responseUrl")))
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))
        givenBuildServerDeletesStateRequestsSuccessfully(groupId)

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    verifyTeamCityServerGetProjectStateIsCalledFor(calledProjectIds)
                    verifyTeamCityServerGetProjectStateIsNotCalledFor(notCalledProjectIds)
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    verifyBuildServerDeleteStateRequestsIsCalled(groupId)
                }
    }

    @ParameterizedTest
    @MethodSource("allSuccessfulBuildsTestCases")
    fun watchStateWhenAllBuildsSuccessful(buildConfig: BuildConfig,
                                          projects: List<Project>,
                                          groupId: String,
                                          calledProjectIds: Set<String>,
                                          notCalledProjectIds: Set<String>,
                                          calledBuildIds: Set<String>,
                                          notCalledBuildIds: Set<String>) {
        val underTest = Application(
                buildConfig = buildConfig,
                jobConfigs = JobConfig(listOf(Job("watchState", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage("All *${groupId}* builds are successful!", "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png", "Success")

        givenBuildServerReturnsStateRequests(listOf(StateRequest(groupId, "${slackServer.baseUrl()}/responseUrl")))
        givenTeamCityServerReturnsStateSuccessfully(projects)
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))
        givenBuildServerDeletesStateRequestsSuccessfully(groupId)

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    verifyTeamCityServerGetProjectStateIsCalledFor(calledProjectIds)
                    verifyTeamCityServerGetProjectStateIsNotCalledFor(notCalledProjectIds)
                    verifyTeamCityServerGetBuildStateIsCalledFor(calledBuildIds)
                    verifyTeamCityServerGetBuildStateIsNotCalledFor(notCalledBuildIds)
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    verifyBuildServerDeleteStateRequestsIsCalled(groupId)
                }
    }

    @ParameterizedTest
    @MethodSource("someFailedBuildsTestCases")
    fun watchStateWhenSomeBuildsFailed(buildConfig: BuildConfig,
                                       projects: List<Project>,
                                       failedBuildsIds: List<String>,
                                       groupId: String,
                                       calledProjectIds: Set<String>,
                                       notCalledProjectIds: Set<String>,
                                       calledBuildIds: Set<String>,
                                       notCalledBuildIds: Set<String>) {
        val underTest = Application(
                buildConfig = buildConfig,
                jobConfigs = JobConfig(listOf(Job("watchState", 0, Long.MAX_VALUE))),
                buildServerConfig = BuildServerConfig(buildServer.baseUrl()),
                teamCityServerConfig = TeamCityServerConfig(teamCityServer.baseUrl(), teamCityUserName, teamCityPassword))
        val reportingMessage = ReportingMessage(failedBuildsIds.joinToString(prefix = "Following *$groupId* builds are currently failing:\n", separator = "\n") { "*$it*" }, "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png", "Failure")

        givenBuildServerReturnsStateRequests(listOf(StateRequest(groupId, "${slackServer.baseUrl()}/responseUrl")))
        givenTeamCityServerReturnsStateSuccessfully(projects)
        givenSlackServerAcceptsReportingMessages("/responseUrl", listOf(reportingMessage))
        givenBuildServerDeletesStateRequestsSuccessfully(groupId)

        underTest.run()

        await().atMost(2, SECONDS)
                .untilAsserted {
                    verifyBuildServerStateRequestsIsCalled()
                    verifyTeamCityServerGetProjectStateIsCalledFor(calledProjectIds)
                    verifyTeamCityServerGetProjectStateIsNotCalledFor(notCalledProjectIds)
                    verifyTeamCityServerGetBuildStateIsCalledFor(calledBuildIds)
                    verifyTeamCityServerGetBuildStateIsNotCalledFor(notCalledBuildIds)
                    verifySlackServerReportMessagesIsCalled("/responseUrl", listOf(reportingMessage))
                    verifyBuildServerDeleteStateRequestsIsCalled(groupId)
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

    private fun givenTeamCityServerReturnsStateSuccessfully(projects: List<Project>) {
        projects.forEach { project ->
            teamCityServer.stubFor(get(urlEqualTo("/projects/id:${project.id}"))
                    .withHeader("Accept", equalTo("application/xml"))
                    .withHeader("Content-Type", equalTo("application/xml"))
                    .withBasicAuth(teamCityUserName, teamCityPassword)
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(
                                    "<project>" +
                                            project.builds.joinToString(prefix = "<buildTypes>", postfix = "</buildTypes>") { "<buildType id=\"${it.id}\"/>" } +
                                            project.subProjects.joinToString(prefix = "<projects>", postfix = "</projects>") { "<project id=\"${it.id}\"/>" } +
                                            "</project>")))

            project.builds
                    .forEach { build ->
                        if (build.status == "NOT_FOUND") {
                            teamCityServer.stubFor(get(urlEqualTo("/builds/buildType:${build.id}"))
                                    .withHeader("Accept", equalTo("application/xml"))
                                    .withHeader("Content-Type", equalTo("application/xml"))
                                    .withBasicAuth(teamCityUserName, teamCityPassword)
                                    .willReturn(aResponse().withStatus(404)))
                        } else {
                            teamCityServer.stubFor(get(urlEqualTo("/builds/buildType:${build.id}"))
                                    .withHeader("Accept", equalTo("application/xml"))
                                    .withHeader("Content-Type", equalTo("application/xml"))
                                    .withBasicAuth(teamCityUserName, teamCityPassword)
                                    .willReturn(aResponse()
                                            .withStatus(200)
                                            .withBody("<build><buildType id=\"${"buildId-" + Random.nextInt()}\" name=\"${build.id}\"></buildType><id>teamCityBuildId</id><state>finished</state><number>${Random.nextInt()}</number><status>${build.status}</status></build>")))
                        }
                    }

            givenTeamCityServerReturnsStateSuccessfully(project.subProjects)
        }
    }

    private fun givenBuildServerDeletesStateRequestsSuccessfully(groupId: String) =
            buildServer.stubFor(delete(urlEqualTo("/state"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson(toJSONString(mapOf("id" to groupId))))
                    .willReturn(aResponse()
                            .withStatus(200)))

    private fun givenTeamCityServerReturnsStateNotExisting(buildIds: Set<String>) =
            buildIds.forEach {
                teamCityServer.stubFor(get(urlEqualTo("/builds/buildType:$it"))
                        .withHeader("Accept", equalTo("application/xml"))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withBasicAuth(teamCityUserName, teamCityPassword)
                        .willReturn(aResponse().withStatus(404)))
            }

    private fun verifyBuildServerStateRequestsIsCalled() =
            buildServer.verify(getRequestedFor(urlEqualTo("/state"))
                    .withHeader("Accept", equalTo("application/json")))

    private fun verifyTeamCityServerGetBuildStateIsCalledFor(buildIds: Set<String>) =
            verifyTeamCityServerGetBuildStateIsCalledFor(buildIds, 1)

    private fun verifyTeamCityServerGetBuildStateIsNotCalledFor(buildIds: Set<String>) =
            verifyTeamCityServerGetBuildStateIsCalledFor(buildIds, 0)

    private fun verifyTeamCityServerGetBuildStateIsCalledFor(buildIds: Set<String>, times: Int) =
            buildIds.forEach {
                teamCityServer.verify(times, getRequestedFor(urlEqualTo("/builds/buildType:$it"))
                        .withHeader("Accept", equalTo("application/xml"))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword)))
            }

    private fun verifyTeamCityServerGetProjectStateIsCalledFor(projectIds: Set<String>) =
            projectIds.forEach {
                teamCityServer.verify(getRequestedFor(urlEqualTo("/projects/id:$it"))
                        .withHeader("Accept", equalTo("application/xml"))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withBasicAuth(BasicCredentials(teamCityUserName, teamCityPassword)))
            }

    private fun verifyTeamCityServerGetProjectStateIsNotCalledFor(projectIds: Set<String>) =
            projectIds.forEach {
                teamCityServer.verify(0, getRequestedFor(urlEqualTo("/projects/id:$it"))
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

    companion object {
        @JvmStatic
        fun groupNotFoundTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId1"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(Project("projectId1", emptyList(), listOf(Build("buildId1", "SUCCESS")))),
                                "NotExistingGroupId",
                                emptySet<String>(),
                                setOf("projectId1")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId([0-9]+)"),
                                                listOf(Project("projectId%s")))),
                                        emptyList()),
                                listOf(Project("projectId2", emptyList(), listOf(Build("buildId1", "SUCCESS")))),
                                "groupId1",
                                setOf("projectId1"),
                                setOf("projectId2")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("([0-9]+)groupId"),
                                                listOf(Project("project%sId")))),
                                        emptyList()),
                                listOf(Project("projectId1", emptyList(), listOf(Build("buildId1", "SUCCESS")))),
                                "2groupId",
                                setOf("project2Id"),
                                setOf("projectId1")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("(\\w+)groupId"),
                                                listOf(Project("%sProjectId")))),
                                        emptyList()),
                                listOf(Project("projectId1", emptyList(), listOf(Build("buildId1", "SUCCESS")))),
                                "theGroupId",
                                setOf("theProjectId"),
                                setOf("projectId1")))

        @JvmStatic
        fun allSuccessfulBuildsTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of(
                                BuildConfig(listOf(teamcity.interactor.app.Group(setOf("groupId1"), emptyList())), emptyList()),
                                emptyList<String>(),
                                "groupId1",
                                emptySet<String>(),
                                emptySet<String>(),
                                emptySet<String>(),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId2"), listOf(Project("projectId1")))), emptyList()),
                                listOf(Project("projectId1", emptyList(), listOf(Build("buildId1", "SUCCESS")))),
                                "groupId2",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId3"), listOf(Project("projectId1")))), emptyList()),
                                listOf(Project(
                                        "projectId1",
                                        emptyList(),
                                        listOf(
                                                Build("buildId1", "SUCCESS"),
                                                Build("buildId2", "SUCCESS")))),
                                "groupId3",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId4"), listOf(Project("projectId1")))), emptyList()),
                                listOf(Project(
                                        "projectId1",
                                        listOf(Project(
                                                "subProjectId1.1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1.1", "SUCCESS"),
                                                        Build("buildId1.2", "SUCCESS")))),
                                        listOf(
                                                Build("buildId1", "SUCCESS"),
                                                Build("buildId2", "SUCCESS"),
                                                Build("buildId3", "SUCCESS")))),
                                "groupId4",
                                setOf("projectId1", "subProjectId1.1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3", "buildId1.1", "buildId1.2"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId5"), listOf(Project("projectId1")))), emptyList()),
                                listOf(Project(
                                        "projectId1",
                                        listOf(Project(
                                                "projectId2",
                                                listOf(Project(
                                                        "projectId3",
                                                        emptyList(),
                                                        listOf(Build("buildId3", "SUCCESS")))),
                                                listOf(Build("buildId2", "SUCCESS")))),
                                        listOf(Build("buildId1", "SUCCESS")))),
                                "groupId5",
                                setOf("projectId1", "projectId2", "projectId3"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(setOf("groupId2"), listOf(Project("projectId1"))),
                                        Group(setOf("groupId6"), listOf(Project("projectId2")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                listOf(Project("projectId3",
                                                        listOf(Project(
                                                                "projectId4",
                                                                emptyList(),
                                                                listOf(Build("buildId4", "SUCCESS")))),
                                                        listOf(Build("buildId3", "SUCCESS")))),
                                                listOf(Build("buildId2", "SUCCESS")))),
                                "groupId6",
                                setOf("projectId2", "projectId3", "projectId4"),
                                setOf("projectId1"),
                                setOf("buildId2", "buildId3", "buildId4"),
                                setOf("buildId1")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId7"), listOf(Project("projectId1")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                listOf(
                                                        Project(
                                                                "projectId3",
                                                                listOf(
                                                                        Project(
                                                                                "projectId4",
                                                                                emptyList(),
                                                                                listOf(Build("buildId4", "SUCCESS")))),
                                                                listOf(Build("buildId3", "SUCCESS")))),
                                                listOf(Build("buildId2", "SUCCESS")))),
                                "groupId7",
                                setOf("projectId1"),
                                setOf("projectId2", "projectId3", "projectId4"),
                                setOf("buildId1"),
                                setOf("buildId2", "buildId3", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId8"), listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId2")))))), emptyList()),
                                listOf(Project(
                                        "projectId1",
                                        emptyList(),
                                        listOf(
                                                Build("buildId1", "SUCCESS"),
                                                Build("buildId2", "FAILURE")))),
                                "groupId8",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1"),
                                setOf("buildId2")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId9"), listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId4", "buildId5")))))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId4",
                                                emptyList(),
                                                listOf(Build("buildId6", "SUCCESS"))),
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "projectId2",
                                                                listOf(
                                                                        Project(
                                                                                "projectId3",
                                                                                emptyList(),
                                                                                listOf(
                                                                                        Build("buildId3", "SUCCESS"),
                                                                                        Build("buildId4", "FAILURE")))),
                                                                listOf(
                                                                        Build("buildId2", "SUCCESS"),
                                                                        Build("buildId5", "FAILURE")))),
                                                listOf(Build("buildId1", "SUCCESS")))),
                                "groupId9",
                                setOf("projectId1", "projectId2", "projectId3"),
                                setOf("projectId4"),
                                setOf("buildId1", "buildId2", "buildId3"),
                                setOf("buildId4", "buildId5", "buildId6")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId10"), listOf(Project("projectId1")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "NOT_FOUND")))),
                                "groupId10",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId11"), listOf(Project("projectId1")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(Project(
                                                        "projectId2",
                                                        listOf(Project(
                                                                "projectId3",
                                                                emptyList(),
                                                                listOf(Build("buildId3", "NOT_FOUND")))),
                                                        listOf(Build("buildId2", "NOT_FOUND")))),
                                                listOf(Build("buildId1", "NOT_FOUND")))),
                                "groupId11",
                                setOf("projectId1", "projectId2", "projectId3"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(setOf("groupId12"), listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId4"))))),
                                        Group(setOf("groupId2"), listOf(Project("project4")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId4",
                                                emptyList(),
                                                listOf(Build("buildId1", "SUCCESS"))),
                                        Project(
                                                "projectId1",
                                                listOf(Project(
                                                        "projectId2",
                                                        listOf(Project(
                                                                "projectId3",
                                                                emptyList(),
                                                                listOf(
                                                                        Build("buildId3", "SUCCESS"),
                                                                        Build("buildId4", "FAILURE")))),
                                                        listOf(
                                                                Build("buildId2", "SUCCESS"),
                                                                Build("buildId6", "NOT_FOUND")))),
                                                listOf(Build("buildId5", "SUCCESS")))),
                                "groupId12",
                                setOf("projectId1", "projectId2", "projectId3"),
                                setOf("projectId4"),
                                setOf("buildId2", "buildId3", "buildId5", "buildId6"),
                                setOf("buildId1", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId13"), listOf(Project("projectId1")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "SUCCESS")))),
                                "GrOupId13",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId14"), listOf(Project("projectId1")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "subProjectId1.1",
                                                                emptyList(),
                                                                listOf(
                                                                        Build("buildId1.1", "SUCCESS"),
                                                                        Build("buildId1.2", "SUCCESS")))),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "SUCCESS"),
                                                        Build("buildId3", "SUCCESS")))),
                                "GROUPID14",
                                setOf("projectId1", "subProjectId1.1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3", "buildId1.1", "buildId1.2"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId15"),
                                                listOf(
                                                        Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId12"))),
                                                        Project("projectId2", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId2.2")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(Project(
                                                        "subProjectId1.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId1.1", "SUCCESS"),
                                                                Build("buildId1.2", "SUCCESS")))),
                                                listOf(
                                                        Build("buildId11", "SUCCESS"),
                                                        Build("buildId12", "FAILURE"),
                                                        Build("buildId13", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                listOf(Project(
                                                        "subProjectId2.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId2.1", "SUCCESS"),
                                                                Build("buildId2.2", "FAILURE")))),
                                                listOf(
                                                        Build("buildId21", "SUCCESS"),
                                                        Build("buildId22", "SUCCESS"),
                                                        Build("buildId23", "SUCCESS")))),
                                "groupId15",
                                setOf("projectId1", "subProjectId1.1", "projectId2", "subProjectId2.1"),
                                emptySet<String>(),
                                setOf("buildId11", "buildId13", "buildId1.1", "buildId1.2", "buildId21", "buildId22", "buildId23", "buildId2.1"),
                                setOf("buildId12", "buildId2.2")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(setOf("groupId16"), listOf(Project("projectId1"), Project("projectId2"))),
                                        Group(setOf("groupId17"), listOf(Project("projectId3")))),
                                        emptyList()),
                                listOf(
                                        Project("projectId1", emptyList(), listOf(Build("buildId11", "SUCCESS"))),
                                        Project("projectId2", emptyList(), listOf(Build("buildId21", "SUCCESS"))),
                                        Project("projectId3", emptyList(), listOf(Build("buildId31", "SUCCESS")))),
                                "groupId16",
                                setOf("projectId1", "projectId2"),
                                setOf("projectId3"),
                                setOf("buildId11", "buildId21"),
                                setOf("buildId31")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId17"), listOf(Project("projectId1", teamcity.interactor.app.Exclusion(projectIds = setOf("projectId1")))))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "FAILURE"),
                                                        Build("buildId2", "SUCCESS")))),
                                "groupId17",
                                emptySet<String>(),
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId18"),
                                                listOf(
                                                        Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId1.1"))),
                                                        Project("projectId2", teamcity.interactor.app.Exclusion(projectIds = setOf("subProjectId2.1")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(Project(
                                                        "subProjectId1.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId1.1", "FAILURE"),
                                                                Build("buildId1.2", "SUCCESS")))),
                                                listOf(
                                                        Build("buildId11", "SUCCESS"),
                                                        Build("buildId12", "SUCCESS"),
                                                        Build("buildId13", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                listOf(Project(
                                                        "subProjectId2.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId2.1", "SUCCESS"),
                                                                Build("buildId2.2", "FAILURE")))),
                                                listOf(
                                                        Build("buildId21", "SUCCESS"),
                                                        Build("buildId22", "SUCCESS"),
                                                        Build("buildId23", "SUCCESS")))),
                                "groupId18",
                                setOf("projectId1", "subProjectId1.1", "projectId2"),
                                setOf("subProjectId2.1"),
                                setOf("buildId1.2", "buildId11", "buildId12", "buildId13", "buildId21", "buildId22", "buildId23"),
                                setOf("buildId1.1", "buildId2.1", "buildId2.2")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId19"),
                                                listOf(
                                                        Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId1"))),
                                                        Project("projectId2"),
                                                        Project("projectId3", teamcity.interactor.app.Exclusion(projectIds = setOf("projectId3"))),
                                                        Project("projectId4")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "FAILURE"))),
                                        Project(
                                                "projectId2",
                                                emptyList(),
                                                listOf(Build("buildId2", "SUCCESS"))),
                                        Project(
                                                "projectId3",
                                                emptyList(),
                                                listOf(Build("buildId3", "FAILURE"))),
                                        Project(
                                                "projectId4",
                                                emptyList(),
                                                listOf(Build("buildId4", "SUCCESS")))),
                                "groupId19",
                                setOf("projectId1", "projectId2", "projectId4"),
                                setOf("projectId3"),
                                setOf("buildId2", "buildId4"),
                                setOf("buildId1", "buildId3")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId20"),
                                                listOf(
                                                        Project("projectId1", teamcity.interactor.app.Exclusion(projectIds = setOf("subProjectId1.1"), buildIds = setOf("buildId12"))),
                                                        Project("projectId2", teamcity.interactor.app.Exclusion(projectIds = setOf("subProjectId2.1")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(Project(
                                                        "subProjectId1.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId1.1", "FAILURE"),
                                                                Build("buildId1.2", "SUCCESS")))),
                                                listOf(
                                                        Build("buildId11", "SUCCESS"),
                                                        Build("buildId12", "FAILURE"),
                                                        Build("buildId13", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                listOf(Project(
                                                        "subProjectId2.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId2.1", "SUCCESS"),
                                                                Build("buildId2.2", "FAILURE")))),
                                                listOf(
                                                        Build("buildId21", "SUCCESS"),
                                                        Build("buildId22", "SUCCESS"),
                                                        Build("buildId23", "SUCCESS")))),
                                "groupId20",
                                setOf("projectId1", "projectId2"),
                                setOf("subProjectId1.1", "subProjectId2.1"),
                                setOf("buildId11", "buildId13", "buildId21", "buildId22", "buildId23"),
                                setOf("buildId1.1", "buildId1.2", "buildId2.1", "buildId2.2")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId21"), listOf(Project("projectId1")))), emptyList()),
                                listOf(Project("projectId1", emptyList(), emptyList())),
                                "groupId21",
                                setOf("projectId1"),
                                emptySet<String>(),
                                emptySet<String>(),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId([0-9]+)"),
                                                listOf(Project("projectId%s")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId22",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "SUCCESS")))),
                                "groupId22",
                                setOf("projectId22"),
                                emptySet<String>(),
                                emptySet<String>(),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId([0-9]+)"),
                                                listOf(Project("projectId%s", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId4")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId23",
                                                listOf(
                                                        Project(
                                                                "subProjectId1.1",
                                                                listOf(
                                                                        Project(
                                                                                "subProjectId1.2",
                                                                                emptyList(),
                                                                                listOf(Build("buildId5", "SUCCESS")))),
                                                                listOf(
                                                                        Build("buildId3", "SUCCESS"),
                                                                        Build("buildId4", "FAILURE"))
                                                        )),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "SUCCESS")))),
                                "groupId23",
                                setOf("projectId23", "subProjectId1.1", "subProjectId1.2"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3", "buildId5"),
                                setOf("buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("(\\w+)groupId"),
                                                listOf(
                                                        Project("%sProjectId"),
                                                        Project("projectId24", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId4")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "theProjectId",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "SUCCESS"))),
                                        Project("projectId24",
                                                listOf(
                                                        Project(
                                                                "projectId25",
                                                                emptyList(),
                                                                listOf(
                                                                        Build("buildId3", "SUCCESS"),
                                                                        Build("buildId4", "FAILURE")))),
                                                listOf(Build("buildId5", "SUCCESS")))),
                                "theGroupId",
                                setOf("theProjectId", "projectId24"),
                                setOf("buildId1", "buildId2", "buildId3", "buildId5"),
                                emptySet<String>(),
                                setOf("buildId4"))
                )

        @JvmStatic
        fun someFailedBuildsTestCases(): Stream<Arguments> =
                Stream.of(
                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId1"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "FAILURE")))),
                                listOf("buildId1"),
                                "groupId1",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId2"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "FAILURE")))),
                                listOf("buildId2"),
                                "groupId2",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId3"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(Project(
                                                        "subProjectId1.1",
                                                        emptyList(),
                                                        listOf(
                                                                Build("buildId1.1", "SUCCESS"),
                                                                Build("buildId1.2", "FAILURE")))),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "SUCCESS"),
                                                        Build("buildId3", "SUCCESS")))),
                                listOf("buildId1.2"),
                                "groupId3",
                                setOf("projectId1", "subProjectId1.1"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3", "buildId1.1", "buildId1.2"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId4"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "projectId2",
                                                                listOf(
                                                                        Project(
                                                                                "projectId3",
                                                                                emptyList(),
                                                                                listOf(Build("buildId3", "SUCCESS")))),
                                                                listOf(Build("buildId2", "SUCCESS")))),
                                                listOf(Build("buildId1", "FAILURE")))),
                                listOf("buildId1"),
                                "groupId4",
                                setOf("projectId1", "projectId2", "projectId3"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId2"),
                                                listOf(Project("projectId1"))),
                                        Group(
                                                setOf("groupId5"),
                                                listOf(Project("projectId2")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "FAILURE"))),
                                        Project(
                                                "projectId2",
                                                listOf(
                                                        Project(
                                                                "projectId3",
                                                                listOf(
                                                                        Project(
                                                                                "projectId4",
                                                                                emptyList(),
                                                                                listOf(Build("buildId3", "FAILURE")))),
                                                                listOf(Build("buildId2", "SUCCESS")))),
                                                listOf(Build("buildId7", "FAILURE")))),
                                listOf("buildId7", "buildId3"),
                                "groupId5",
                                setOf("projectId2", "projectId3", "projectId4"),
                                setOf("projectId1"),
                                setOf("buildId7", "buildId2", "buildId3"),
                                setOf("buildId1")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId7"),
                                                listOf(Project("projectId4"))),
                                        Group(
                                                setOf("groupId6"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId4",
                                                emptyList(),
                                                listOf(Build("buildId4", "SUCCESS"))),
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "projectId2",
                                                                listOf(
                                                                        Project(
                                                                                "projectId3",
                                                                                emptyList(),
                                                                                listOf(Build("buildId3", "FAILURE")))),
                                                                listOf(Build("buildId2", "FAILURE")))),
                                                listOf(Build("buildId1", "SUCCESS")))),
                                listOf("buildId2", "buildId3"),
                                "groupId6",
                                setOf("projectId1", "projectId2", "projectId3"),
                                setOf("projectId4"),
                                setOf("buildId1", "buildId2", "buildId3"),
                                setOf("buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId7"),
                                                listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId1")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "FAILURE"),
                                                        Build("buildId2", "FAILURE"),
                                                        Build("buildId3", "FAILURE"),
                                                        Build("buildId4", "FAILURE")))),
                                listOf("buildId2", "buildId3", "buildId4"),
                                "groupId7",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId2", "buildId3", "buildId4"),
                                setOf("buildId1")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId7"),
                                                listOf(Project("projectId4"))),
                                        Group(
                                                setOf("groupId8"),
                                                listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId3")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId4",
                                                emptyList(),
                                                listOf(Build("buildId4", "SUCCESS"))),
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "projectId2",
                                                                listOf(
                                                                        Project(
                                                                                "projectId3",
                                                                                emptyList(),
                                                                                listOf(Build("buildId3", "FAILURE")))),
                                                                listOf(Build("buildId2", "FAILURE")))),
                                                listOf(Build("buildId1", "SUCCESS")))),
                                listOf("buildId2"),
                                "groupId8",
                                setOf("projectId1", "projectId2", "projectId3"),
                                setOf("projectId4"),
                                setOf("buildId1", "buildId2"),
                                setOf("buildId3", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId8"),
                                                listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId1"))))),
                                        Group(
                                                setOf("groupId9"),
                                                listOf(Project("projectId2", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId4")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "FAILURE"))),
                                        Project(
                                                "projectId2",
                                                listOf(
                                                        Project(
                                                                "projectId3",
                                                                listOf(
                                                                        Project(
                                                                                "projectId4",
                                                                                emptyList(),
                                                                                listOf(Build("buildId4", "FAILURE")))),
                                                                listOf(Build("buildId3", "FAILURE")))),
                                                listOf(Build("buildId2", "FAILURE")))),
                                listOf("buildId2", "buildId3"),
                                "groupId9",
                                setOf("projectId2", "projectId3", "projectId4"),
                                setOf("projectId1"),
                                setOf("buildId2", "buildId3"),
                                setOf("buildId1", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId8"),
                                                listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId1"))))),
                                        Group(
                                                setOf("groupId10"),
                                                listOf(Project("projectId2", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId4")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "FAILURE"))),
                                        Project(
                                                "projectId2",
                                                listOf(
                                                        Project("projectId3",
                                                                listOf(
                                                                        Project(
                                                                                "projectId4",
                                                                                emptyList(),
                                                                                listOf(Build("buildId4", "FAILURE")))),
                                                                listOf(Build("buildId3", "NOT_FOUND")))),
                                                listOf(Build("buildId2", "FAILURE")))),
                                listOf("buildId2"),
                                "groupId10",
                                setOf("projectId2", "projectId3", "projectId4"),
                                setOf("projectId1"),
                                setOf("buildId2", "buildId3"),
                                setOf("buildId1", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId11"),
                                                listOf(Project("projectId1", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId1")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "NOT_FOUND"),
                                                        Build("buildId2", "FAILURE"),
                                                        Build("buildId3", "NOT_FOUND"),
                                                        Build("buildId4", "FAILURE")))),
                                listOf("buildId2", "buildId4"),
                                "groupId11",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId2", "buildId3", "buildId4"),
                                setOf("buildId1")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId12"),
                                                listOf(Project("projectId1")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(Build("buildId1", "FAILURE")))),
                                listOf("buildId1"),
                                "GrOupId12",
                                setOf("projectId1"),
                                emptySet<String>(),
                                setOf("buildId1"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId14"),
                                                listOf(
                                                        Project("projectId1"),
                                                        Project("projectId2")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "projectId1.1",
                                                                listOf(
                                                                        Project(
                                                                                "projectId1.2",
                                                                                emptyList(),
                                                                                listOf(Build("buildId1", "SUCCESS")))),
                                                                listOf(Build("buildId2", "SUCCESS")))),
                                                listOf(Build("buildId3", "FAILURE"))),
                                        Project(
                                                "projectId2",
                                                listOf(
                                                        Project(
                                                                "projectId2.1",
                                                                listOf(
                                                                        Project(
                                                                                "projectId2.2",
                                                                                emptyList(),
                                                                                listOf(Build("buildId4", "FAILURE")))),
                                                                listOf(Build("buildId5", "SUCCESS")))),
                                                listOf(Build("buildId6", "FAILURE")))),
                                listOf("buildId3", "buildId6", "buildId4"),
                                "groupId14",
                                setOf("projectId1", "projectId1.1", "projectId1.2", "projectId2", "projectId2.1", "projectId2.2"),
                                emptySet<String>(),
                                setOf("buildId3", "buildId2", "buildId1", "buildId6", "buildId5", "buildId4"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId15"),
                                                listOf(
                                                        Project("projectId1"),
                                                        Project("projectId2"),
                                                        Project("projectId3"),
                                                        Project("projectId4")))),
                                        emptyList()),
                                listOf(
                                        Project("projectId1", emptyList(), listOf(Build("buildId1", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId2", "SUCCESS"),
                                                        Build("buildId3", "FAILURE"),
                                                        Build("buildId4", "SUCCESS"))),
                                        Project("projectId3", emptyList(), listOf(Build("buildId5", "FAILURE"))),
                                        Project(
                                                "projectId4",
                                                listOf(
                                                        Project(
                                                                "projectId4.1",
                                                                listOf(
                                                                        Project(
                                                                                "projectId4.2",
                                                                                emptyList(),
                                                                                listOf(Build("buildId6", "FAILURE")))),
                                                                listOf(Build("buildId7", "SUCCESS")))),
                                                listOf(Build("buildId8", "FAILURE")))),
                                listOf("buildId3", "buildId5", "buildId8", "buildId6"),
                                "groupId15",
                                setOf("projectId1", "projectId2", "projectId3", "projectId4", "projectId4.1", "projectId4.2"),
                                emptySet<String>(),
                                setOf("buildId1", "buildId2", "buildId3", "buildId4", "buildId5", "buildId8", "buildId7", "buildId6"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId16"),
                                                listOf(
                                                        Project("projectId1", teamcity.interactor.app.Exclusion(projectIds = setOf("projectId1"))),
                                                        Project("projectId2")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "NOT_FOUND"),
                                                        Build("buildId2", "FAILURE"),
                                                        Build("buildId3", "NOT_FOUND"),
                                                        Build("buildId4", "FAILURE"))),
                                        Project(
                                                "projectId2",
                                                emptyList(),
                                                listOf(Build("buildId5", "FAILURE")))),
                                listOf("buildId5"),
                                "groupId16",
                                setOf("projectId2"),
                                setOf("projectId1"),
                                setOf("buildId5"),
                                setOf("buildId1", "buildId2", "buildId3", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId17"),
                                                listOf(
                                                        Project("projectId1"),
                                                        Project("projectId2", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId3"))),
                                                        Project("projectId3"),
                                                        Project("projectId4", teamcity.interactor.app.Exclusion(projectIds = setOf("projectId4.1"), buildIds = setOf("buildId8")))))),
                                        emptyList()),
                                listOf(
                                        Project("projectId1", emptyList(), listOf(Build("buildId1", "SUCCESS"))),
                                        Project(
                                                "projectId2",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId2", "SUCCESS"),
                                                        Build("buildId3", "FAILURE"),
                                                        Build("buildId4", "SUCCESS"))),
                                        Project("projectId3", emptyList(), listOf(Build("buildId5", "FAILURE"))),
                                        Project(
                                                "projectId4",
                                                listOf(
                                                        Project(
                                                                "projectId4.1",
                                                                listOf(
                                                                        Project(
                                                                                "projectId4.2",
                                                                                emptyList(),
                                                                                listOf(Build("buildId6", "FAILURE")))),
                                                                listOf(Build("buildId7", "SUCCESS")))),
                                                listOf(Build("buildId8", "FAILURE")))),
                                listOf("buildId5"),
                                "groupId17",
                                setOf("projectId1", "projectId2", "projectId3", "projectId4"),
                                setOf("projectId4.1", "projectId4.2"),
                                setOf("buildId1", "buildId2", "buildId4", "buildId5"),
                                setOf("buildId6", "buildId7", "buildId8")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(setOf("groupId4"), listOf(Project("projectId1"))),
                                        Group(setOf("groupId18"), listOf(
                                                Project("projectId4", teamcity.interactor.app.Exclusion(projectIds = setOf("projectId4"))),
                                                Project("projectId5", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId6")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(
                                                        Project(
                                                                "projectId2",
                                                                listOf(
                                                                        Project(
                                                                                "projectId3",
                                                                                emptyList(),
                                                                                listOf(Build("buildId3", "SUCCESS")))),
                                                                listOf(Build("buildId2", "SUCCESS")))),
                                                listOf(Build("buildId1", "FAILURE"))),
                                        Project(
                                                "projectId4",
                                                emptyList(),
                                                listOf(Build("buildId4", "FAILURE"))),
                                        Project(
                                                "projectId5",
                                                listOf(
                                                        Project(
                                                                "projectId6",
                                                                listOf(
                                                                        Project(
                                                                                "projectId7",
                                                                                emptyList(),
                                                                                listOf(
                                                                                        Build("buildId5", "FAILURE"),
                                                                                        Build("buildId6", "FAILURE"),
                                                                                        Build("buildId7", "NOT_FOUND")))),
                                                                emptyList()),
                                                        Project(
                                                                "projectId8",
                                                                emptyList(),
                                                                listOf(Build("buildId8", "FAILURE")))),
                                                emptyList())),
                                listOf("buildId5", "buildId8"),
                                "groupId18",
                                setOf("projectId5", "projectId6", "projectId7", "projectId8"),
                                setOf("projectId1", "projectId2", "projectId3", "projectId4"),
                                setOf("buildId5", "buildId7", "buildId8"),
                                setOf("buildId1", "buildId2", "buildId3", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(Group(setOf("groupId19"), listOf(Project("projectId1")))), emptyList()),
                                listOf(
                                        Project(
                                                "projectId1",
                                                listOf(Project("projectId2", emptyList(), listOf(Build("buildId1", "FAILURE")))),
                                                emptyList())),
                                listOf("buildId1"),
                                "groupId19",
                                setOf("projectId1", "projectId2"),
                                emptySet<String>(),
                                setOf("buildId1"),
                                emptySet<String>()),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("groupId([0-9]+)"),
                                                listOf(Project("projectId%s")))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "projectId20",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "FAILURE"))),
                                        Project(
                                                "projectId21",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId3", "SUCCESS"),
                                                        Build("buildId4", "FAILURE")))),
                                listOf("buildId2"),
                                "groupId20",
                                setOf("projectId20"),
                                setOf("projectId21"),
                                setOf("buildId1", "buildId2"),
                                setOf("buildId3", "buildId4")),

                        Arguments.of(
                                BuildConfig(listOf(
                                        Group(
                                                setOf("(\\w+)groupId"),
                                                listOf(
                                                        Project("%sProjectId"),
                                                        Project("projectId24", teamcity.interactor.app.Exclusion(buildIds = setOf("buildId3")))))),
                                        emptyList()),
                                listOf(
                                        Project(
                                                "theProjectId",
                                                emptyList(),
                                                listOf(
                                                        Build("buildId1", "SUCCESS"),
                                                        Build("buildId2", "SUCCESS"))),
                                        Project("projectId24",
                                                listOf(
                                                        Project(
                                                                "projectId25",
                                                                emptyList(),
                                                                listOf(
                                                                        Build("buildId3", "SUCCESS"),
                                                                        Build("buildId4", "FAILURE")))),
                                                listOf(Build("buildId5", "SUCCESS")))),
                                listOf("buildId4"),
                                "theGroupId",
                                setOf("theProjectId", "projectId24"),
                                setOf("buildId1", "buildId2", "buildId4"),
                                emptySet<String>(),
                                setOf("buildId3")))
    }
}