package teamcity.interactor.app

import teamcity.interactor.client.buildserver.BUILD_SERVER_CLIENT_FACTORY
import teamcity.interactor.client.buildserver.BuildName
import teamcity.interactor.client.reporting.*
import teamcity.interactor.client.teamcity.TEAM_CITY_CLIENT_FACTORY
import teamcity.interactor.client.teamcity.TeamCityBuild
import teamcity.interactor.client.teamcity.TeamCityBuildRequest
import teamcity.interactor.client.teamcity.TeamCityBuildType
import teamcity.interactor.config.ConfigReader
import kotlin.concurrent.fixedRateTimer

data class BuildConfig(val id: String, val names: Set<String>)
data class BuildInformation(val teamCityBuild: TeamCityBuild, val responseUrl: String)

private val BUILD_CONFIGS: List<BuildConfig> = ConfigReader().config("build-config.json")

fun main() {
    val buildServerClient = BUILD_SERVER_CLIENT_FACTORY.client()
    val teamCityClient = TEAM_CITY_CLIENT_FACTORY.client()
    var builds = listOf<BuildInformation>()

    @Synchronized
    fun setBuilds(supplier: () -> List<BuildInformation>) {
        builds = supplier()
    }

    fixedRateTimer("triggerWaitingBuilds", false, 0L, 5000) {
        setBuilds {
            builds.plus(
                    buildServerClient.getBuilds()
                            .mapNotNull { buildServerBuild ->
                                val buildInformation = BUILD_CONFIGS.firstOrNull { buildConfig -> buildConfig.names.any { teamCityBuildName -> teamCityBuildName.equals(buildServerBuild.id, true) } }
                                        ?.let { teamCityClient.build(TeamCityBuildRequest(TeamCityBuildType(it.id))) }
                                        ?.let { TeamCityBuild(it.buildType, it.id, "none", it.status) }
                                        ?.let { BuildInformation(it, buildServerBuild.responseUrl) }
                                        ?: run {
                                            REPORTING_CLIENT_FACTORY.client(buildServerBuild.responseUrl).report(Report(
                                                    listOf(ReportingMessage(
                                                            text = Text(text = "${buildServerBuild.id} build is not found"),
                                                            buildStatus = BuildStatus.NotFound))))
                                            null
                                        }

                                println(buildServerBuild.responseUrl)
                                buildServerClient.deleteBuild(BuildName(buildServerBuild.id))
                                buildInformation
                            }
                            .toList())
        }
        println("triggerWaitingBuilds: $builds")
    }

    fixedRateTimer("watchBuilds", false, 0L, 5000) {
        setBuilds {
            builds
                    .map { buildInformation ->
                        val latestTeamCityBuild = teamCityClient.status(buildInformation.teamCityBuild.id)
                        latestTeamCityBuild.takeUnless { buildInformation.teamCityBuild.state == it.state }
                                ?.let {
                                    REPORTING_CLIENT_FACTORY.client(buildInformation.responseUrl).report(Report(
                                            listOf(ReportingMessage(
                                                    text = Text(text = "${latestTeamCityBuild.buildType.id} build is ${latestTeamCityBuild.state}"),
                                                    buildStatus = BuildStatus.of(latestTeamCityBuild.state, latestTeamCityBuild.status)))))
                                }
                        BuildInformation(latestTeamCityBuild, buildInformation.responseUrl)
                    }
        }
        println("watchBuilds: $builds")
    }
}
