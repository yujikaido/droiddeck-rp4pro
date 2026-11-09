package com.droiddeck.launcher.session

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * a player log app.log:41 and :82: "game storage: private directories could not be prepared; internal only this
 * session" (NoSuchFileException in SecondaryLibrary.migrate) on two session starts that ran at once with a third.
 * Each migration copies through one shared `<target>.migrating` folder, and a second start deleted it mid-copy.
 * Two starts at once must both get the card's binds, with every file copied once.
 */
class SecondaryLibraryConcurrentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun twoStartsAtOnceBothGetTheCardAndTheCopyIsComplete() {
        repeat(3) { round ->
            val files = tmp.newFolder("files$round")
            val library = tmp.newFolder("card$round")
            val source = File(library, "steamapps/compatdata").apply { mkdirs() }
            repeat(40) { d ->
                val dir = File(source, "app$d/pfx/drive_c").apply { mkdirs() }
                repeat(50) { f -> File(dir, "f$f.txt").writeText("x".repeat(64)) }
            }
            val go = CountDownLatch(1)
            val errors = List(2) { AtomicReference<Throwable?>() }
            val binds = List(2) { AtomicReference<List<String>?>() }
            val threads = List(2) { i ->
                Thread {
                    go.await()
                    try {
                        binds[i].set(SecondaryLibrary.binds(files, library))
                    } catch (t: Throwable) {
                        errors[i].set(t)
                    }
                }.apply { start() }
            }
            go.countDown()
            threads.forEach { it.join(60_000) }
            errors.forEach { assertNull("round $round: ${it.get()}", it.get()) }
            assertEquals(binds[0].get(), binds[1].get())
            val target = File(SecondaryLibrary.privateRoot(files, library), "steamapps/compatdata")
            assertEquals(2000, target.walkTopDown().count { it.isFile })
            assertFalse(File(target.parentFile, "compatdata.migrating").exists())
        }
    }
}
