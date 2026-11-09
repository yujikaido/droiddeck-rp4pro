package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolverOrderTest {
    private val order = LinuxNetworkLinkComponent.Companion::resolverOrder

    @Test fun theActiveNetworksServersComeFirstAndPublicOnesStayAfterThem() {
        assertEquals(listOf("192.168.1.1", "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888"),
            order(listOf("192.168.1.1"), emptyList(), emptyList()))
    }

    @Test fun anotherNetworksServersGoBeforeThePublicOnes() {
        // A network where the active one lists no DNS server (or only ones that fail): the Wi-Fi's
        // own server is asked before Google's and Cloudflare's.
        assertEquals(listOf("10.0.0.1", "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "10.0.0.254"),
            order(emptyList(), listOf("10.0.0.1"), listOf("10.0.0.254")))
    }

    @Test fun gatewaysComeLastAndNothingRepeatsOrGoesPastSix() {
        val servers = order(listOf("192.168.1.1", "fd00::1"), listOf("192.168.1.1", "192.168.2.1"),
            listOf("192.168.1.1", "192.168.2.254"))
        assertEquals(listOf("192.168.1.1", "fd00::1", "192.168.2.1", "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888"), servers)
    }

    @Test fun withNothingDiscoveredTheFileIsTheSameAsBefore() {
        // The old order: the active network's servers, then the three public ones, at most six.
        val own = listOf("172.16.0.1", "172.16.0.2")
        assertEquals((own + LinuxNetworkLinkComponent.PUBLIC_DNS).distinct().take(6), order(own, emptyList(), emptyList()))
    }

    @Test fun ipv4ServersAreListedBeforeIpv6() {
        assertEquals(listOf("192.168.1.1", "fe80::1", "2001:db8::53"),
            LinuxNetworkLinkComponent.ipv4First(listOf("fe80::1", "192.168.1.1", "2001:db8::53")))
    }
}
