package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VkBridgeStuckPipelinesTest {
    @Test
    fun lookingForTheStuckPipelinesListCreatesNoBridgeFolder() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "vkbridge-pipelines")
        dir.deleteRecursively()
        val list = VkBridgeComponent.stuckPipelines(context)
        assertEquals(File(dir, "stuck-pipelines"), list)
        assertFalse(list.exists())
        assertFalse(dir.exists())
    }
}
