package il.kavmonitor.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

const val USER_AGENT = "kav-monitor-app/0.1 (personal bus watch; Android)"

/** Platform engine (OkHttp on Android/JVM). */
expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

fun createHttpClient(): HttpClient = platformHttpClient {
    expectSuccess = true
    install(ContentNegotiation) { json(ApiJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = 25_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 25_000
    }
    defaultRequest {
        headers.append(HttpHeaders.Accept, "application/json")
        headers.append(HttpHeaders.UserAgent, USER_AGENT)
    }
}
