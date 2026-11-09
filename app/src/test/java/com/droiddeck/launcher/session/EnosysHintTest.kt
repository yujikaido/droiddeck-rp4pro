package com.droiddeck.launcher.session

import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Test

class EnosysHintTest {
    @Test fun suggestsRunningWithoutSeccompOnlyWhileThatIsOff() {
        assertEquals(R.plurals.session_hint_enosys, EnosysHint.plural(prootNoSeccomp = false))
    }

    @Test fun withTheSwitchAlreadyOnItSaysToTurnItOff() {
        assertEquals(R.plurals.session_hint_enosys_no_seccomp, EnosysHint.plural(prootNoSeccomp = true))
    }
}
