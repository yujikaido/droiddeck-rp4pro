package com.droiddeck.launcher.core

import android.content.Context
import com.droiddeck.launcher.R
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/* Common network failures as a short line in the player's language, instead of Java's English text
 * ("Unable to resolve host "api.github.com": No address associated with hostname"). The raw text
 * still goes to the log; anything not listed here keeps its own message. */
object NetworkError {
    enum class Kind { NO_HOST, TIMEOUT, REFUSED, SSL }

    /** The kind of [e] or of one of its causes; null when it isn't a plain network failure. */
    fun kind(e: Throwable?): Kind? {
        var t = e
        var depth = 0
        while (t != null && depth++ < 8) {
            when (t) {
                is UnknownHostException -> return Kind.NO_HOST
                is SocketTimeoutException -> return Kind.TIMEOUT
                is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return Kind.REFUSED
                is SSLException -> return Kind.SSL
                // OkHttp's call timeout is a bare InterruptedIOException("timeout").
                is InterruptedIOException -> if (t.message?.contains("timeout", ignoreCase = true) == true) return Kind.TIMEOUT
            }
            t = t.cause.takeIf { it !== t }
        }
        return null
    }

    fun text(context: Context, kind: Kind): String = context.getString(when (kind) {
        Kind.NO_HOST -> R.string.net_err_no_host
        Kind.TIMEOUT -> R.string.net_err_timeout
        Kind.REFUSED -> R.string.net_err_refused
        Kind.SSL -> R.string.net_err_ssl
    })

    /** The translated line for [e], or null when it isn't one of the common network failures. */
    fun describe(context: Context, e: Throwable?): String? = kind(e)?.let { text(context, it) }
}
