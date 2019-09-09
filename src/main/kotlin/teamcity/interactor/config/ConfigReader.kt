package teamcity.interactor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import teamcity.interactor.app.BuildConfig

class ConfigReader {
    // TODO make this method generic
    fun config(configFile: String): List<BuildConfig> = ObjectMapper()
            .registerModule(KotlinModule())
            .readValue(this::class.java.getResource(configFile))

    fun <T> config(configFile: String, clazz: Class<T>): T = ObjectMapper()
            .registerModule(KotlinModule())
            .readValue(this::class.java.getResource(configFile), clazz)
}
