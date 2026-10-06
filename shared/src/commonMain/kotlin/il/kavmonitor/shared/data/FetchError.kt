package il.kavmonitor.shared.data

import kotlin.concurrent.Volatile

/** curlbus answered HTTP 5xx for [stopCode]. Some stops do this all the time (seen: 25141, 27117, 20876, 20725). */
class StopServerException(val stopCode: String, val status: Int, cause: Throwable? = null) :
    Exception("curlbus HTTP $status for stop $stopCode", cause)

/** What went wrong, in user terms. The UI maps these to short Hebrew texts; raw details go to the log only. */
enum class ErrorKind { SERVER, NETWORK, TIMEOUT, SOURCE, OTHER }

data class FetchError(val kind: ErrorKind, val stopCode: String? = null)

object FetchErrors {
    fun classify(e: Throwable, stopCode: String? = null): FetchError {
        var t: Throwable? = e
        while (t != null) {
            val name = t::class.simpleName.orEmpty()
            when {
                t is StopServerException -> return FetchError(ErrorKind.SERVER, t.stopCode)
                t is io.ktor.client.plugins.ServerResponseException -> return FetchError(ErrorKind.SERVER, stopCode)
                t is io.ktor.client.plugins.HttpRequestTimeoutException || name.contains("Timeout", ignoreCase = true) ->
                    return FetchError(ErrorKind.TIMEOUT, stopCode)
                name.contains("UnknownHost") || name.contains("UnresolvedAddress") || name.contains("ConnectException") ||
                    name.contains("NoRouteToHost") || name.contains("IOException") || name.contains("SocketException") ->
                    return FetchError(ErrorKind.NETWORK, stopCode)
            }
            t = t.cause.takeIf { it !== t }
        }
        return FetchError(ErrorKind.OTHER, stopCode)
    }

    fun isServerError(e: Throwable): Boolean = classify(e).kind == ErrorKind.SERVER
}

/** Raw error details for developers (Android: logcat). Never shown in the UI. */
object ErrorLog {
    @Volatile
    var sink: (String, Throwable?) -> Unit = { msg, e -> println("kav-monitor: $msg ${e ?: ""}") }

    fun w(msg: String, e: Throwable? = null) = runCatching { sink(msg, e) }.let { }
}
