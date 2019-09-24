package teamcity.interactor.app

import feign.FeignException
import teamcity.interactor.client.buildserver.Name
import teamcity.interactor.client.buildserver.getBuildServerClient
import teamcity.interactor.client.reporting.*
import teamcity.interactor.client.reporting.BuildStatus.*
import teamcity.interactor.client.teamcity.TeamCityBuild
import teamcity.interactor.client.teamcity.TeamCityBuildRequest
import teamcity.interactor.client.teamcity.TeamCityBuildType
import teamcity.interactor.client.teamcity.getTeamCityClient
import teamcity.interactor.config.ConfigReader
import java.util.*
import kotlin.concurrent.fixedRateTimer

data class BuildConfig(val groups: List<Group>, val builds: List<Build>)
data class Group(val names: Set<String>, val buildIds: Set<String>)
data class Build(val id: String, val names: Set<String>)
data class JobConfig(val name: String, val initialDelay: Long, val period: Long)
data class BuildServerConfig(val baseUrl: String)
data class TeamCityServerConfig(val baseUrl: String, val username: String, val password: String)

class Application internal constructor(private val buildConfig: BuildConfig = ConfigReader().config("build-config.json", BuildConfig::class.java),
                                       private val jobConfigs: List<JobConfig> = ConfigReader().jobConfig("job-config.json"),
                                       buildServerConfig: BuildServerConfig = ConfigReader().config("build-server-config.json", BuildServerConfig::class.java),
                                       teamCityServerConfig: TeamCityServerConfig = ConfigReader().config("teamcity-server-config.json", TeamCityServerConfig::class.java)) {

    private var builds: List<BuildInformation> = listOf()
    private val teamCityClient = getTeamCityClient(teamCityServerConfig.baseUrl, teamCityServerConfig.username, teamCityServerConfig.password)
    private val buildServerClient = getBuildServerClient(buildServerConfig.baseUrl)

    data class BuildInformation(val teamCityBuild: TeamCityBuild, val responseUrl: String)

    fun run() {
        job("triggerBuilds") {
            setBuilds {
                builds.plus(
                        buildServerClient.getBuildRequests()
                                .mapNotNull { buildRequest ->
                                    val buildInformation = buildConfig.builds.firstOrNull { build -> build.names.any { teamCityBuildName -> teamCityBuildName.equals(buildRequest.id, true) } }
                                            ?.let { teamCityClient.build(TeamCityBuildRequest(TeamCityBuildType(it.id))) }
                                            ?.let { TeamCityBuild(it.buildType, it.id, it.number, "none", it.status) }
                                            ?.let { BuildInformation(it, buildRequest.responseUrl) }
                                            ?: run {
                                                reportingClient(buildRequest.responseUrl).report(Report(
                                                        listOf(ReportingMessage(
                                                                text = Text(text = "${buildRequest.id} build is not found"),
                                                                buildStatus = NotFound))))
                                                null
                                            }

                                    println(buildRequest.responseUrl)
                                    buildServerClient.deleteBuildRequest(Name(buildRequest.id))
                                    buildInformation
                                })
            }
            println("triggerWaitingBuilds: $builds")
        }

        job("watchBuilds") {
            setBuilds {
                builds.mapNotNull { buildInformation ->
                    val latestTeamCityBuild = teamCityClient.status(buildInformation.teamCityBuild.id)
                    latestTeamCityBuild
                            .takeUnless { buildInformation.teamCityBuild.state == it.state }
                            ?.let {
                                val buildStatus = BuildStatus.of(latestTeamCityBuild.state, latestTeamCityBuild.status)
                                reportingClient(buildInformation.responseUrl).report(Report(
                                        listOf(ReportingMessage(
                                                text = Text(text = "*${latestTeamCityBuild.buildType.name}* build ${latestTeamCityBuild.number?.let { "*$it* " }.orEmpty()}is ${buildStatus.displayName}"),
                                                buildStatus = buildStatus))))
                            }
                    if (latestTeamCityBuild.state != "finished") BuildInformation(latestTeamCityBuild, buildInformation.responseUrl) else null
                }
            }
            println("watchBuilds: $builds")
        }

        job("cancelBuilds") {
            buildServerClient.getCancelRequests()
                    .groupBy { cancelRequest -> buildConfig.builds.firstOrNull { build -> build.names.any { name -> name.equals(cancelRequest.id, true) } }?.id }
                    .forEach { entry ->
                        entry.key
                                ?.let { builds.filter { buildInformation -> it == buildInformation.teamCityBuild.buildType.id }.map { it.teamCityBuild.id } }
                                ?.forEach {
                                    try {
                                        teamCityClient.cancel("buildQueue", it)
                                    } catch (e: FeignException) {
                                        if (e.status() == 404) teamCityClient.cancel("builds", it)
                                    }
                                }
                                ?: run {
                                    entry.value.forEach {
                                        reportingClient(it.responseUrl).report(Report(
                                                listOf(ReportingMessage(
                                                        text = Text(text = "No queued/running ${it.id} build is found"),
                                                        buildStatus = NotFound))))
                                    }
                                }

                        entry.value
                                .map { it.id }
                                .distinct()
                                .map { Name(it) }
                                .forEach { buildServerClient.deleteCancelRequest(it) }
                    }
        }

        job("watchState") {
            buildServerClient.getStateRequests()
                    .forEach { stateRequest ->
                        val failedBuilds = buildConfig.groups
                                .first { group -> group.names.any { name -> name.equals(stateRequest.id, true) } }
                                .buildIds
                                .map { teamCityClient.state(it) }
                                .filter { Success != BuildStatus.of(it.state, it.status) }

                        if (failedBuilds.isNotEmpty()) {
                            reportingClient(stateRequest.responseUrl).report(Report(
                                    listOf(ReportingMessage(
                                            text = Text(text = failedBuilds.joinToString(prefix = "Following *${stateRequest.id}* builds are currently failing:\n", separator = "\n") { "*${it.buildType.name}*" }),
                                            buildStatus = Failure))))

                        } else {
                            reportingClient(stateRequest.responseUrl).report(Report(
                                    listOf(ReportingMessage(
                                            text = Text(text = "All *${stateRequest.id}* builds are successful!"),
                                            buildStatus = Success))))
                        }
                        buildServerClient.deleteStateRequest(Name(stateRequest.id))
                    }
        }
    }

    private fun job(name: String, timerTask: TimerTask.() -> Unit) =
            jobConfigs
                    .firstOrNull { it.name == name }
                    ?.let { fixedRateTimer(it.name, false, it.initialDelay, it.period, timerTask) }

    @Synchronized
    fun setBuilds(supplier: () -> List<BuildInformation>) = apply { builds = supplier() }

    fun getBuilds() = builds
}

fun main() = Application().run()
