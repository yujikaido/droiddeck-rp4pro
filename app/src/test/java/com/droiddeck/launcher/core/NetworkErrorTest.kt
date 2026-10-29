package com.droiddeck.launcher.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkErrorTest {
    @Test fun commonNetworkFailuresAreNamed() {
        assertEquals(NetworkError.Kind.NO_HOST,
            NetworkError.kind(UnknownHostException("Unable to resolve host \"api.github.com\": No address associated with hostname")))
        assertEquals(NetworkError.Kind.TIMEOUT, NetworkError.kind(SocketTimeoutException("timeout")))
        assertEquals(NetworkError.Kind.TIMEOUT, NetworkError.kind(InterruptedIOException("timeout")))
        assertEquals(NetworkError.Kind.REFUSED, NetworkError.kind(ConnectException("Failed to connect to /140.82.121.6:443")))
        assertEquals(NetworkError.Kind.SSL, NetworkError.kind(SSLHandshakeException("Chain validation failed")))
    }

    @Test fun aWrappedCauseIsFound() {
        assertEquals(NetworkError.Kind.NO_HOST, NetworkError.kind(IOException("check failed", UnknownHostException("api.github.com"))))
    }

    @Test fun otherFailuresKeepTheirOwnText() {
        assertNull(NetworkError.kind(IOException("HTTP 403")))
        assertNull(NetworkError.kind(InterruptedIOException("interrupted")))
        assertNull(NetworkError.kind(null))
    }
}
