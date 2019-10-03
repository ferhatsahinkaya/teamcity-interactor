package teamcity.interactor.app

import feign.FeignException
import teamcity.interactor.client.buildserver.Name
import teamcity.interactor.client.buildserver.getBuildServerClient
import teamcity.interactor.client.reporting.*
import teamcity.interactor.client.reporting.BuildStatus.*
import teamcity.interactor.client.teamcity.*
import teamcity.interactor.config.ConfigReader
import java.util.*
import kotlin.concurrent.fixedRateTimer
import kotlin.text.RegexOption.IGNORE_CASE

data class BuildConfig(val groups: List<Group>, val builds: List<Build>)
data class Group(val names: Set<String>, val projects: List<Project>)
data class Project(val id: String, val exclusion: Exclusion = Exclusion())
data class Exclusion(val projectIds: Set<String> = emptySet(), val buildIds: Set<String> = emptySet())
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

        fun failedBuilds(projects: List<Project>): Set<String> {
            return projects
                    .flatMap { project ->
                        val teamCityProject = if (!project.exclusion.projectIds.contains(project.id)) teamCityClient.project(project.id) else TeamCityProject(emptyList(), emptyList())
                        (teamCityProject.buildTypes ?: emptyList())
                                .filterNot { project.exclusion.buildIds.contains(it.id) }
                                .map { it.id }
                                .mapNotNull {
                                    try {
                                        val state = teamCityClient.state(it)
                                        if (Success != BuildStatus.of(state.state, state.status)) state.buildType.name else null
                                    } catch (e: FeignException) {
                                        if (e.status() == 404) {
                                            println("Build $it not found. Ignoring the build failure")
                                            null
                                        } else throw e
                                    }
                                }.plus(failedBuilds(teamCityProject.projects?.map { Project(it.id, project.exclusion) }
                                        ?: emptyList()))
                    }
                    .toSet()
        }

        job("watchState") {
            buildServerClient.getStateRequests()
                    .forEach { stateRequest ->
                        var group = buildConfig.groups.firstOrNull { group -> group.names.any { id -> id.equals(stateRequest.id, true) } }
                        var projects = group?.projects
                        var aProjectFound = true

                        if (group == null) {
                            group = buildConfig.groups
                                    .firstOrNull { g ->
                                        g.names
                                                .any {
                                                    (it.toRegex(IGNORE_CASE)
                                                            .find(stateRequest.id)
                                                            ?.groupValues?.size ?: 0) > 1
                                                }
                                    }

                            if (group != null) {
                                val replacement = group.names.first { v ->
                                    (v.toRegex(IGNORE_CASE).find(stateRequest.id)
                                            ?.groupValues?.size ?: 0) > 1
                                }
                                        .toRegex(IGNORE_CASE).find(stateRequest.id)?.groupValues?.get(1) ?: ""

                                projects = group.projects
                                        .map { project ->
                                            Project(
                                                    String.format(project.id, replacement),
                                                    project.exclusion)
                                        }
                                println("Projects $projects")

                                aProjectFound = projects.any {
                                    try {
                                        if (it.exclusion.projectIds.contains(it.id)) false
                                        else {
                                            teamCityClient.project(it.id)
                                            true
                                        }
                                    } catch (e: FeignException) {
                                        false
                                    }
                                }
                            }
                        }

                        if (group == null || !aProjectFound) {
                            reportingClient(stateRequest.responseUrl).report(Report(
                                    listOf(ReportingMessage(
                                            text = Text(text = "*${stateRequest.id}* group is not found"),
                                            buildStatus = NotFound))))
                        } else {
                            val failedBuilds = failedBuilds(projects!!)

                            if (failedBuilds.isNotEmpty()) {
                                reportingClient(stateRequest.responseUrl).report(Report(
                                        listOf(ReportingMessage(
                                                text = Text(text = failedBuilds.joinToString(prefix = "Following *${stateRequest.id}* builds are currently failing:\n", separator = "\n") { "*$it*" }),
                                                buildStatus = Failure))))

                            } else {
                                reportingClient(stateRequest.responseUrl).report(Report(
                                        listOf(ReportingMessage(
                                                text = Text(text = "All *${stateRequest.id}* builds are successful!"),
                                                buildStatus = Success))))
                            }
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
