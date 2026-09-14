package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxGuestPathResolverTest {
    private val host = "/data/local/tmp/eta"
    private val mounts = listOf(
        SharedFolderMount(name = "Download", sourcePath = "/storage/emulated/0/Download"),
    )

    @Test
    fun workspaceFileMapsToHost() {
        assertEquals(
            "$host/chat-video-frame.jpg",
            resolve("/workspace/chat-video-frame.jpg"),
        )
    }

    @Test
    fun workspaceRootMapsToHost() {
        assertEquals(host, resolve("/workspace"))
        assertEquals(host, resolve("/workspace/"))
    }

    @Test
    fun workspaceDotsAndDuplicateSlashesCollapse() {
        assertEquals("$host/a.jpg", resolve("/workspace/./a.jpg"))
        assertEquals("$host/dir/a.jpg", resolve("/workspace//dir///a.jpg"))
        assertEquals("$host/a.jpg", resolve("/workspace/dir/../a.jpg"))
    }

    @Test
    fun fileUriWorkspaceMapsToHost() {
        assertEquals("$host/a.jpg", resolve("file:///workspace/a.jpg"))
    }

    @Test
    fun sharedMountMapsToAndroidSource() {
        assertEquals(
            "/storage/emulated/0/Download",
            resolve("/workspace/mounts/Download"),
        )
        assertEquals(
            "/storage/emulated/0/Download/cover.jpg",
            resolve("/workspace/mounts/Download/cover.jpg"),
        )
        assertEquals(
            "/storage/emulated/0/Download/sub/a.jpg",
            resolve("/workspace/mounts/Download/sub/a.jpg"),
        )
    }

    @Test
    fun unknownMountStaysUnderWorkspaceHost() {
        assertEquals(
            "$host/mounts/Other/a.jpg",
            resolve("/workspace/mounts/Other/a.jpg"),
        )
        assertEquals("$host/mounts", resolve("/workspace/mounts"))
    }

    @Test
    fun androidAndContentPathsStayUnchanged() {
        assertEquals("/sdcard/Pictures/a.jpg", resolve("/sdcard/Pictures/a.jpg"))
        assertEquals(
            "content://media/external/images/media/1",
            resolve("content://media/external/images/media/1"),
        )
        assertEquals("/workspace-not/a.jpg", resolve("/workspace-not/a.jpg"))
    }

    @Test
    fun escapedWorkspaceDoesNotMap() {
        assertEquals("/workspace/../../etc/passwd", resolve("/workspace/../../etc/passwd"))
    }

    @Test
    fun minisPathsMapToWorkspaceHost() {
        assertEquals("$host/index.html", resolve("/var/minis/workspace/index.html"))
        assertEquals("$host/offloads/env.sh", resolve("/var/minis/offloads/env.sh"))
        assertEquals("$host/browser/a.jpg", resolve("/var/minis/browser/a.jpg"))
        assertEquals(
            "/data/data/app/files/minis/offloads/env.sh",
            LinuxGuestPathResolver.resolveAndroidPath(
                path = "/var/minis/offloads/env.sh",
                workspaceHost = host,
                offloadsHost = "/data/data/app/files/minis/offloads",
            ),
        )
        assertEquals(
            "/data/data/app/files/skills/bilibili-hub/SKILL.md",
            LinuxGuestPathResolver.resolveAndroidPath(
                path = "/var/minis/skills/bilibili-hub/SKILL.md",
                workspaceHost = host,
                skillsHost = "/data/data/app/files/skills",
            ),
        )
        assertEquals("$host/index.html", resolve("minis://workspace/index.html"))
    }

    @Test
    fun chrootUsesRootWorkspaceBind() {
        val filesDir = java.io.File("/tmp/eta-files")
        assertEquals(
            "/data/local/tmp/eta",
            LinuxGuestPathResolver.workspaceHost(
                filesDir = filesDir,
                linuxReady = true,
                backend = LinuxExecutionBackend.CHROOT,
            ).path.replace('\\', '/'),
        )
        assertEquals(
            TerminalPrivateStorage.workspace(filesDir),
            LinuxGuestPathResolver.workspaceHost(
                filesDir = filesDir,
                linuxReady = true,
                backend = LinuxExecutionBackend.PROOT,
            ),
        )
        assertEquals(
            TerminalPrivateStorage.workspace(filesDir),
            LinuxGuestPathResolver.workspaceHost(
                filesDir = filesDir,
                linuxReady = false,
                backend = LinuxExecutionBackend.CHROOT,
            ),
        )
    }

    private fun resolve(path: String): String =
        LinuxGuestPathResolver.resolveAndroidPath(path, host, mounts)
}
