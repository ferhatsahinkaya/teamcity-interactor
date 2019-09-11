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
private data class BuildInformation(val teamCityBuild: TeamCityBuild, val responseUrl: String)

private val BUILD_CONFIGS: List<BuildConfig> = ConfigReader().config("build-config.json")

fun main() {
    val buildServerClient = BUILD_SERVER_CLIENT_FACTORY.client()
    val teamCityClient = TEAM_CITY_CLIENT_FACTORY.client()
    var builds = listOf<BuildInformation>()

    fixedRateTimer("triggerWaitingBuilds", false, 0L, 5000) {
        builds = builds.plus(
                buildServerClient.getBuilds()
                        .filter { BUILD_CONFIGS.any { buildConfig -> buildConfig.names.any { teamCityBuildName -> teamCityBuildName.toLowerCase() == it.id.toLowerCase() } } }
                        .map { buildServerBuild ->
                            BUILD_CONFIGS
                                    .first { buildConfig -> buildConfig.names.any { teamCityBuildName -> teamCityBuildName.toLowerCase() == buildServerBuild.id.toLowerCase() } }
                                    .let { teamCityClient.build(TeamCityBuildRequest(TeamCityBuildType(it.id))) }
                                    .let { TeamCityBuild(it.buildType, it.id, "none", it.status) }
                                    .let {
                                        println(buildServerBuild.responseUrl)
                                        buildServerClient.deleteBuild(BuildName(buildServerBuild.id))
                                        BuildInformation(it, buildServerBuild.responseUrl)
                                    }
                        }
                        .toList())
        println("triggerWaitingBuilds: $builds")
    }

    fixedRateTimer("watchBuilds", false, 0L, 5000) {
        builds = builds
                .map { buildInformation ->
                    val latestTeamCityBuild = teamCityClient.status(buildInformation.teamCityBuild.id)
                    latestTeamCityBuild.takeUnless { buildInformation.teamCityBuild.state == it.state }
                            ?.let {
                                REPORTING_CLIENT_FACTORY.client(buildInformation.responseUrl).report(Report(
                                        listOf(ReportingMessage(
                                                text = Text(text = "${latestTeamCityBuild.buildType.id} build is ${latestTeamCityBuild.state}"),
                                                accessory = getAccessory(latestTeamCityBuild)))))
                            }
                    BuildInformation(latestTeamCityBuild, buildInformation.responseUrl)
                }
        println("watchBuilds: $builds")
    }
}

private fun getAccessory(latestTeamCityBuild: TeamCityBuild): Accessory {
    return when {
        latestTeamCityBuild.state == "queued" -> Accessory(
                imageUrl = "https://cdn1.iconfinder.com/data/icons/company-business-people-1/32/busibess_people-40-512.png",
                altText = "Queued")
        latestTeamCityBuild.state == "running" -> Accessory(
                imageUrl = "https://cdn3.iconfinder.com/data/icons/living/24/254_running_activity_fitness-512.png",
                altText = "Running")
        latestTeamCityBuild.state == "finished" && latestTeamCityBuild.status == "SUCCESS" -> Accessory(
                imageUrl = "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/check-circle-green-512.png",
                altText = "Success")
        latestTeamCityBuild.state == "finished" && latestTeamCityBuild.status != "SUCCESS" -> Accessory(
                imageUrl = "https://cdn0.iconfinder.com/data/icons/social-messaging-ui-color-shapes/128/close-circle-red-512.png",
                altText = "Failure")
        else -> Accessory(
                imageUrl = "https://cdn2.iconfinder.com/data/icons/freecns-cumulus/16/519660-164_QuestionMark-512.png",
                altText = "Unknown"
        )
    }
}