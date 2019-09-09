package teamcity.interactor.app

import teamcity.interactor.client.buildserver.BUILD_SERVER_CLIENT_FACTORY
import teamcity.interactor.client.buildserver.BuildName
import teamcity.interactor.client.reporting.REPORTING_CLIENT_FACTORY
import teamcity.interactor.client.reporting.Report
import teamcity.interactor.client.teamcity.TEAM_CITY_CLIENT_FACTORY
import teamcity.interactor.client.teamcity.TeamCityBuildRequest
import teamcity.interactor.client.teamcity.TeamCityBuildType
import teamcity.interactor.config.ConfigReader
import kotlin.concurrent.fixedRateTimer

data class BuildConfig(val id: String, val names: Set<String>)
private data class BuildInformation(val id: String, var state: String, val responseUrl: String)

private val BUILD_CONFIGS: List<BuildConfig> = ConfigReader().config("build-config.json")

fun main() {
    val buildServerClient = BUILD_SERVER_CLIENT_FACTORY.client()
    val teamCityClient = TEAM_CITY_CLIENT_FACTORY.client()
    val builds = mutableListOf<BuildInformation>()

    fixedRateTimer("triggerWaitingBuilds", false, 0L, 5000) {
        buildServerClient.getBuilds()
                .forEach { buildServerBuild ->
                    BUILD_CONFIGS
                            .firstOrNull { buildConfig -> buildConfig.names.any { teamCityBuildName -> teamCityBuildName.toLowerCase() == buildServerBuild.id.toLowerCase() } }
                            ?.let { teamCityClient.build(TeamCityBuildRequest(TeamCityBuildType(it.id))) }
                            ?.let { BuildInformation(it.id, "none", buildServerBuild.responseUrl) }
                            ?.let { builds.add(it) }
                    buildServerClient.deleteBuild(BuildName(buildServerBuild.id))
                }
    }

    fixedRateTimer("watchBuilds", false, 0L, 5000) {
        builds.forEach { build ->
            teamCityClient.status(build.id)
                    .takeUnless { it.state == build.state }
                    ?.let {
                        build.state = it.state
                        REPORTING_CLIENT_FACTORY.client(build.responseUrl).report(Report("${it.buildType.id} teamcity build is ${it.state}"))
                    }
        }
    }
}