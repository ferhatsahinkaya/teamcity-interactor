package teamcity.interactor.app

import feign.FeignException
import teamcity.interactor.client.buildserver.BUILD_SERVER_CLIENT_FACTORY
import teamcity.interactor.client.buildserver.BuildName
import teamcity.interactor.client.buildserver.BuildServerClient
import teamcity.interactor.client.reporting.*
import teamcity.interactor.client.teamcity.*
import teamcity.interactor.config.ConfigReader
import kotlin.concurrent.fixedRateTimer

data class BuildConfig(val id: String, val names: Set<String>)

private val BUILD_CONFIGS: List<BuildConfig> = ConfigReader().config("build-config.json")

class Application internal constructor(private val buildServerClient: BuildServerClient = BUILD_SERVER_CLIENT_FACTORY.client(),
                                       private val teamCityClient: TeamCityClient = TEAM_CITY_CLIENT_FACTORY.client(),
                                       private var builds: List<BuildInformation> = listOf()) {

    data class BuildInformation(val teamCityBuild: TeamCityBuild, val responseUrl: String)

    fun run() {
        fixedRateTimer("triggerBuilds", false, 0L, 10000) {
            setBuilds {
                builds.plus(
                        buildServerClient.getBuildRequests()
                                .mapNotNull { buildRequest ->
                                    val buildInformation = BUILD_CONFIGS.firstOrNull { buildConfig -> buildConfig.names.any { teamCityBuildName -> teamCityBuildName.equals(buildRequest.id, true) } }
                                            ?.let { teamCityClient.build(TeamCityBuildRequest(TeamCityBuildType(it.id))) }
                                            ?.let { TeamCityBuild(it.buildType, it.id, it.number, "none", it.status) }
                                            ?.let { BuildInformation(it, buildRequest.responseUrl) }
                                            ?: run {
                                                REPORTING_CLIENT_FACTORY.client(buildRequest.responseUrl).report(Report(
                                                        listOf(ReportingMessage(
                                                                text = Text(text = "${buildRequest.id} build is not found"),
                                                                buildStatus = BuildStatus.NotFound))))
                                                null
                                            }

                                    println(buildRequest.responseUrl)
                                    buildServerClient.deleteBuildRequest(BuildName(buildRequest.id))
                                    buildInformation
                                }
                                .toList())
            }
            println("triggerWaitingBuilds: $builds")
        }

        fixedRateTimer("watchBuilds", false, 0L, 5000) {
            setBuilds {
                builds.mapNotNull { buildInformation ->
                    val latestTeamCityBuild = teamCityClient.status(buildInformation.teamCityBuild.id)
                    latestTeamCityBuild.takeUnless { buildInformation.teamCityBuild.state == it.state }
                            ?.let {
                                val buildStatus = BuildStatus.of(latestTeamCityBuild.state, latestTeamCityBuild.status)
                                REPORTING_CLIENT_FACTORY.client(buildInformation.responseUrl).report(Report(
                                        listOf(ReportingMessage(
                                                text = Text(text = "*${latestTeamCityBuild.buildType.id}* build ${latestTeamCityBuild.number?.let { "*$it* " }.orEmpty()}is ${buildStatus.displayName}"),
                                                buildStatus = buildStatus))))
                            }
                    if (latestTeamCityBuild.state != "finished") BuildInformation(latestTeamCityBuild, buildInformation.responseUrl) else null
                }
            }
            println("watchBuilds: $builds")
        }

        fixedRateTimer("cancelBuilds", false, 0L, 10000) {
            buildServerClient.getCancelRequests()
                    .distinctBy { it.id }
                    .forEach { cancelRequest ->
                        BUILD_CONFIGS.firstOrNull { buildConfig -> buildConfig.names.any { teamCityBuildName -> teamCityBuildName.equals(cancelRequest.id, true) } }
                                ?.id
                                ?.let { buildConfigId -> builds.filter { buildInformation -> buildConfigId == buildInformation.teamCityBuild.buildType.id }.map { it.teamCityBuild.id } }
                                ?.takeIf { it.isNotEmpty() }
                                ?.forEach {
                                    try {
                                        teamCityClient.cancel("buildQueue", it)
                                    } catch (e: FeignException) {
                                        if (e.status() == 404) teamCityClient.cancel("builds", it)
                                    }
                                }
                                ?: run {
                                    REPORTING_CLIENT_FACTORY.client(cancelRequest.responseUrl).report(Report(
                                            listOf(ReportingMessage(
                                                    text = Text(text = "No queued/running ${cancelRequest.id} build is found"),
                                                    buildStatus = BuildStatus.NotFound))))
                                }
                        buildServerClient.deleteCancelRequest(BuildName(cancelRequest.id))
                    }
        }
    }

    @Synchronized
    fun setBuilds(supplier: () -> List<BuildInformation>) = apply { builds = supplier() }
}

fun main() = Application().run()
