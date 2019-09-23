package teamcity.interactor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import teamcity.interactor.app.JobConfig

class ConfigReader {
    // TODO make this method generic
    fun jobConfig(configFile: String): List<JobConfig> = ObjectMapper()
            .registerModule(KotlinModule())
            .readValue(this::class.java.getResource(configFile))

    fun <T> config(configFile: String, clazz: Class<T>): T = ObjectMapper()
            .registerModule(KotlinModule())
            .readValue(this::class.java.getResource(configFile), clazz)
}
