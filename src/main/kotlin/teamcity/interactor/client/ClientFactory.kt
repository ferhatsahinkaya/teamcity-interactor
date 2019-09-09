package teamcity.interactor.client

interface ClientFactory<T> {
    fun client(): T
}

interface ClientFactoryForUrl<T> {
    fun client(url: String): T
}
