package teamcity.interactor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

class ConfigReader {
    fun <T> config(configFile: String, clazz: Class<T>): T = ObjectMapper()
            .registerModule(KotlinModule())
            .readValue(this::class.java.getResource(configFile), clazz)
}
