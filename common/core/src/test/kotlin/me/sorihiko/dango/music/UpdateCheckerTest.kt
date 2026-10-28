package me.sorihiko.dango.music

import me.sorihiko.dango.update.UpdateChecker
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun ownReleaseSelectsMatchingLoaderAsset() {
        val page = "https://github.com/kuxiaole/MineKUmusic/releases/tag/v1.7.1"
        val jar = "https://github.com/kuxiaole/MineKUmusic/releases/download/v1.7.1/MineKUmusic-1.7.1+26.3-fabric.jar"
        val release = UpdateChecker.Release("1.7.1", page, listOf("MineKUmusic-1.7.1+26.3-fabric.jar" to jar))
        assertEquals(jar, UpdateChecker.infoFor(release, "1.7.0", "26.3-fabric")!!.download)
        assertEquals(page, UpdateChecker.infoFor(release, "1.7.0", "26.2-fabric")!!.download)
        assertNull(UpdateChecker.infoFor(release, "1.7.1", "26.3-fabric"))
        assertTrue(UpdateChecker.GITHUB_LATEST.contains("kuxiaole/MineKUmusic"))
    }
}
