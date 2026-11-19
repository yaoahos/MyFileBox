package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 删除进行中下载任务时，必须把嵌套子文件与重命名文件都清掉。 */
class TransferCleanupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun downloadTask(
        target: String,
        children: List<TransferChild> = emptyList(),
        sources: List<String> = emptyList(),
        createdDirs: List<String> = emptyList(),
        to: String = "",
        status: TransferStatus = TransferStatus.RUNNING
    ): TransferTask = TransferTask(
        id = "t1", mode = TransferMode.DOWNLOAD, title = "Backup", detail = "", total = children.size,
        target = target, sources = sources, children = children, createdDirs = createdDirs.toSet(),
        to = to, status = status
    )

    @Test
    fun `文件夹下载按子项路径清理嵌套与重命名文件及part`() {
        val root = "/sdcard/Download"
        val task = downloadTask(
            target = root,
            children = listOf(
                TransferChild(
                    key = "Backup/inner/a.jpg", name = "Backup/inner/a.jpg", path = "/remote/a.jpg",
                    status = TransferChildStatus.SUCCESS, target = "$root/Backup/inner/a.jpg"
                ),
                TransferChild(
                    key = "Backup/b.txt", name = "Backup/b.txt", path = "/remote/b.txt",
                    status = TransferChildStatus.SUCCESS, target = "$root/Backup/b (1).txt"
                ),
                // 传到一半、还没登记 target 的文件：按相对路径推导也要清掉。
                TransferChild(
                    key = "Backup/c.mp4", name = "Backup/c.mp4", path = "/remote/c.mp4",
                    status = TransferChildStatus.RUNNING
                )
            )
        )
        val paths = downloadCleanupPaths(task)
        val files = paths.files.map { it.path }.toSet()
        assertTrue(files.contains(File("$root/Backup/inner/a.jpg").absolutePath))
        assertTrue(files.contains(File("$root/Backup/b (1).txt").absolutePath))
        assertTrue(files.contains(File("$root/Backup/c.mp4").absolutePath))
        // 目标目录本身绝不能进删除列表。
        assertTrue(!files.contains(File(root).absolutePath))
        assertEquals(
            setOf("a.jpg.part", "b (1).txt.part", "c.mp4.part"),
            paths.parts.map { it.name }.toSet()
        )
    }

    @Test
    fun `同名跳过的文件保留但其part仍清理`() {
        val root = "/sdcard/Download"
        val kept = File(root, "old.apk").absolutePath
        val task = downloadTask(
            target = root,
            children = listOf(
                TransferChild(
                    key = "old.apk", name = "old.apk", path = "/remote/old.apk",
                    status = TransferChildStatus.SUCCESS, target = kept, preExisting = true
                )
            )
        )
        val paths = downloadCleanupPaths(task)
        assertTrue(!paths.files.any { it.absolutePath == kept })
        assertEquals(listOf("old.apk.part"), paths.parts.map { it.name })
    }

    @Test
    fun `老单文件记录没有子项时按来源名清理`() {
        val target = "/sdcard/Download/photo.jpg"
        val task = downloadTask(target = target, sources = listOf("/remote/photo.jpg"))
        val paths = downloadCleanupPaths(task)
        assertTrue(paths.files.any { it.absolutePath == File(target).absolutePath })
        assertTrue(paths.parts.any { it.name == "photo.jpg.part" })
    }

    @Test
    fun `新建目录由深到浅回收且目标目录本身不删`() {
        val root = "/sdcard/Download/MFBXTest"
        val task = downloadTask(
            target = root,
            children = listOf(
                TransferChild(key = "A/B/a.txt", name = "A/B/a.txt", path = "/r/a.txt",
                    status = TransferChildStatus.SUCCESS, target = "$root/A/B/a.txt")
            ),
            createdDirs = listOf("A/B", "A", "Empty", "Empty/sub")
        )
        val dirs = downloadCleanupPaths(task).dirs.map { it.path }
        // 由深到浅：子目录排在父目录前面，删空目录时才不会留下空壳。
        assertTrue(dirs.indexOf(File("$root/Empty/sub").absolutePath) < dirs.indexOf(File("$root/Empty").absolutePath))
        assertTrue(dirs.indexOf(File("$root/A/B").absolutePath) < dirs.indexOf(File("$root/A").absolutePath))
        assertTrue(!dirs.contains(File(root).absolutePath))
    }

    @Test
    fun `单文件下载与目标目录之外的路径都不会进空目录清理`() {
        val root = "/sdcard/Download/photo.jpg"
        val task = downloadTask(
            target = root, sources = listOf("/r/photo.jpg"),
            createdDirs = listOf("..", "", "/etc", "sub")
        )
        val dirs = downloadCleanupPaths(task).dirs.map { it.path }
        // 只保留目标目录之下的真实子目录；逃逸路径与空串全部过滤掉。
        assertEquals(listOf(File(root, "sub").absolutePath), dirs)
    }

    @Test
    fun `已暂停任务取消时仍能清理已下载文件与空目录`() {
        val root = "/sdcard/Download/PausedCheck"
        val task = downloadTask(
            target = root,
            children = listOf(
                TransferChild(
                    key = "A/a.txt", name = "A/a.txt", path = "/r/a.txt",
                    status = TransferChildStatus.PAUSED, target = "$root/A/a.txt"
                ),
                TransferChild(
                    key = "A/b.txt", name = "A/b.txt", path = "/r/b.txt",
                    status = TransferChildStatus.PAUSED
                )
            ),
            createdDirs = listOf("A", "Empty"),
            status = TransferStatus.PAUSED
        )
        val paths = downloadCleanupPaths(task)
        assertEquals(
            setOf(
                File("$root/A/a.txt").absolutePath,
                File("$root/A/b.txt").absolutePath
            ),
            paths.files.map { it.absolutePath }.toSet()
        )
        assertEquals(setOf("a.txt.part", "b.txt.part"), paths.parts.map { it.name }.toSet())
        assertTrue(paths.dirs.any { it.absolutePath == File(root, "Empty").absolutePath })
    }

    @Test
    fun `只有空目录的下载任务也返回目录清理路径`() {
        val root = folder.newFolder("EmptyOnly")
        // 空目录占位不会进 children；整条记录只有 createdDirs。
        val task = downloadTask(
            target = root.absolutePath,
            createdDirs = listOf("A", "A/B", "Empty")
        )
        val paths = downloadCleanupPaths(task)
        // 没有任何真实文件时删除逻辑不能提前返回，否则这些空目录会一直留着。
        assertTrue(paths.files.none { it.isFile })
        assertTrue(paths.parts.none { it.isFile })
        assertEquals(
            setOf("A", "A/B", "Empty").map { File(root, it).absolutePath }.toSet(),
            paths.dirs.map { it.absolutePath }.toSet()
        )
        val dirPaths = paths.dirs.map { it.absolutePath }
        assertTrue(dirPaths.indexOf(File(root, "A/B").absolutePath) <
            dirPaths.indexOf(File(root, "A").absolutePath))
    }

    /**
     * 下载「只有 1 个文件的文件夹 + 空目录」时，target 会被改成那个文件本身的路径，
     * 而 createdDirs 相对的是下载目录 (to)。空目录清理必须以 to 为根，否则拼出的
     * 路径错位，删任务后空文件夹一直留着。
     */
    @Test
    fun `单文件加空目录时按目标目录回收空文件夹`() {
        val dest = folder.newFolder("MFBEmptyDL")
        val file = File(dest, "a.txt").absolutePath
        val task = downloadTask(
            target = file, // fileCount==1 时 target 指向落地文件，不是目录
            to = dest.absolutePath,
            children = listOf(
                TransferChild(key = "MFBEmptyCheck/a.txt", name = "MFBEmptyCheck/a.txt",
                    path = "/r/a.txt", status = TransferChildStatus.SUCCESS, target = file)
            ),
            createdDirs = listOf("MFBEmptyCheck", "MFBEmptyCheck/emptydir")
        )
        val dirs = downloadCleanupPaths(task).dirs.map { it.absolutePath }
        assertEquals(
            setOf(
                File(dest, "MFBEmptyCheck/emptydir").absolutePath,
                File(dest, "MFBEmptyCheck").absolutePath
            ),
            dirs.toSet()
        )
        // 由深到浅，删空目录时不留空壳。
        assertTrue(dirs.indexOf(File(dest, "MFBEmptyCheck/emptydir").absolutePath) <
            dirs.indexOf(File(dest, "MFBEmptyCheck").absolutePath))
    }

    /** 文件夹任务的产物 = 目标目录 + 文件夹名，与子项落在哪一层无关。 */
    @Test
    fun `下载文件夹的产物是目标目录下的那个文件夹`() {
        val task = TransferTask(
            id = "d1", mode = TransferMode.DOWNLOAD, title = "Avatar", detail = "", total = 2,
            to = "/sdcard/Download", target = "/sdcard/Download", folderTask = true,
            status = TransferStatus.SUCCESS,
            sourceItems = listOf("/remote/Avatar"),
            children = listOf(
                TransferChild(key = "Avatar/a.jpg", name = "Avatar/a.jpg", path = "/remote/a.jpg",
                    target = "/sdcard/Download/Avatar/a.jpg"),
                TransferChild(key = "Avatar/sub/b.jpg", name = "Avatar/sub/b.jpg", path = "/remote/b.jpg",
                    target = "/sdcard/Download/Avatar/sub/b.jpg")
            )
        )
        assertEquals("/sdcard/Download/Avatar", transferTargetPathOf(task))
    }

    /** 上传文件夹：即使 target 被逐文件回调改成夹内某个文件，产物仍是那个文件夹。 */
    @Test
    fun `上传文件夹的产物是目标目录下的那个文件夹`() {
        val task = TransferTask(
            id = "u3", mode = TransferMode.UPLOAD, title = "Avatar", detail = "", total = 2,
            to = "/photo", target = "/photo/Avatar/sub/b.jpg", folderTask = true,
            status = TransferStatus.SUCCESS,
            sourceItems = listOf("/local/Avatar"),
            children = listOf(
                TransferChild(key = "Avatar/a.jpg", name = "Avatar/a.jpg", path = "/local/a.jpg",
                    target = "/photo/Avatar/a.jpg")
            )
        )
        assertEquals("/photo/Avatar", transferTargetPathOf(task))
    }

    /** 上传到远端根目录：产物是 /Avatar，不是根目录，也不是夹内那个文件。 */
    @Test
    fun `上传到远端根目录时产物仍是那个文件夹`() {
        val task = TransferTask(
            id = "u5", mode = TransferMode.UPLOAD, title = "Avatar", detail = "", total = 2,
            to = "/", target = "/Avatar/a.jpg", folderTask = true,
            status = TransferStatus.SUCCESS,
            sourceItems = listOf("/local/Avatar")
        )
        assertEquals("/Avatar", transferTargetPathOf(task))
    }

    /** 单文件记录：产物就是 target 本身（含同名冲突改名后的名字）。 */
    @Test
    fun `单文件记录的产物就是文件本身`() {
        val task = TransferTask(
            id = "d2", mode = TransferMode.DOWNLOAD, title = "a.txt", detail = "", total = 1,
            to = "/sdcard/Download", target = "/sdcard/Download/a (1).txt",
            status = TransferStatus.SUCCESS, sourceItems = listOf("/remote/a.txt")
        )
        assertFalse(transferIsFolderTask(task))
        assertEquals("/sdcard/Download/a (1).txt", transferTargetPathOf(task))
    }

    @Test
    fun `上传任务不返回任何本地清理路径`() {
        val task = TransferTask(
            id = "u1", mode = TransferMode.UPLOAD, title = "x", detail = "", total = 1,
            target = "/remote/x", uploadedTargets = setOf("/remote/x")
        )
        val paths = downloadCleanupPaths(task)
        assertTrue(paths.files.isEmpty())
        assertTrue(paths.parts.isEmpty())
    }

    /**
     * 老记录 / 快照丢了 uploadedTargets 时，必须靠子项目标把远端文件找回来，
     * 否则「删除记录」只删了记录、远端整批文件一份不动。
     */
    @Test
    fun `上传记录缺少 uploadedTargets 时按子项目标清理`() {
        val task = TransferTask(
            id = "u8", mode = TransferMode.UPLOAD, title = "Folder", detail = "", total = 2,
            to = "/photo", target = "/photo",
            children = listOf(
                TransferChild(key = "Folder/a.txt", name = "Folder/a.txt", path = "/local/a.txt",
                    target = "/photo/Folder/a.txt"),
                TransferChild(key = "Folder/b.txt", name = "Folder/b.txt", path = "/local/b.txt",
                    target = "/photo/Folder/b.txt")
            )
        )
        assertEquals(
            setOf("/photo/Folder/a.txt", "/photo/Folder/b.txt"),
            uploadCleanupTargets(task).toSet()
        )
    }

    /** uploadedTargets 有值时不再并入子项，避免把同名跳过的用户文件一并删掉。 */
    @Test
    fun `有 uploadedTargets 时不并入子项目标`() {
        val task = TransferTask(
            id = "u9", mode = TransferMode.UPLOAD, title = "Folder", detail = "", total = 2,
            to = "/photo", uploadedTargets = setOf("/photo/Folder/a.txt"),
            children = listOf(
                TransferChild(key = "Folder/a.txt", name = "Folder/a.txt", path = "/local/a.txt",
                    target = "/photo/Folder/a.txt"),
                TransferChild(key = "Folder/own.txt", name = "Folder/own.txt", path = "/local/own.txt",
                    target = "/photo/Folder/own.txt", preExisting = true)
            )
        )
        assertEquals(listOf("/photo/Folder/a.txt"), uploadCleanupTargets(task))
    }

    @Test
    fun `下载任务没有远端清理目标`() {
        val task = downloadTask(target = "/sdcard/Download", sources = listOf("/r/a.txt"))
        assertTrue(uploadCleanupTargets(task).isEmpty())
    }

    /**
     * 目录选择器给回来的远端路径可能没有前导斜杠，记录里就成了「Backup/Temp/Temp」。
     * 所有「是否远端路径」的判断都靠 startsWith("/")，必须在这里统一补正。
     */
    @Test
    fun `远端路径统一补成绝对形式`() {
        assertEquals("/Backup/Temp/Temp", normalizeRemotePath("Backup/Temp/Temp"))
        assertEquals("/Backup/Temp/Temp", normalizeRemotePath("/Backup/Temp/Temp/"))
        assertEquals("/Backup", normalizeRemotePath("  /Backup  "))
        // 空串与根目录都算根，不能返回 ""。
        assertEquals("/", normalizeRemotePath(""))
        assertEquals("/", normalizeRemotePath("/"))
        // 本地路径本来就带前导斜杠，不能被改坏。
        assertEquals("/sdcard/Download", normalizeRemotePath("/sdcard/Download"))
        // WebDAV 的远端就是绝对 URL：加前导斜杠会把上传 / 删除全部弄坏。
        assertEquals("https://dav.example.cn/photo", normalizeRemotePath("https://dav.example.cn/photo/"))
        assertEquals("http://192.168.1.10:5005/dav", normalizeRemotePath("http://192.168.1.10:5005/dav"))
    }

    /** 老记录里 uploadedTargets 是相对路径，删除前必须补成绝对，否则远端一个都删不掉。 */
    @Test
    fun `老记录里的相对上传目标在清理时补成绝对路径`() {
        val task = TransferTask(
            id = "u11", mode = TransferMode.UPLOAD, title = "Temp", detail = "", total = 2,
            to = "Backup/Temp", target = "/Backup/Temp/Temp",
            uploadedTargets = setOf("Backup/Temp/Temp/a.apk", "Backup/Temp/Temp/b.apk")
        )
        assertEquals(
            setOf("/Backup/Temp/Temp/a.apk", "/Backup/Temp/Temp/b.apk"),
            uploadCleanupTargets(task).toSet()
        )
        // 目录回收也走同一套目标，必须能还原出 /Backup/Temp/Temp。
        assertEquals(listOf("/Backup/Temp/Temp"), uploadDirCleanupPaths(task))
    }

    /** 删除文件与回收目录必须用同一套目标，否则会出现「文件删了、空文件夹留着」。 */
    @Test
    fun `上传记录缺少 uploadedTargets 时仍能回收空目录`() {
        val task = TransferTask(
            id = "u10", mode = TransferMode.UPLOAD, title = "Folder", detail = "", total = 2,
            to = "/photo",
            children = listOf(
                TransferChild(key = "Folder/a.txt", name = "Folder/a.txt", path = "/local/a.txt",
                    target = "/photo/Folder/a.txt"),
                TransferChild(key = "Folder/sub/b.txt", name = "Folder/sub/b.txt", path = "/local/b.txt",
                    target = "/photo/Folder/sub/b.txt")
            )
        )
        assertEquals(listOf("/photo/Folder/sub", "/photo/Folder"), uploadDirCleanupPaths(task))
    }

    /**
     * 下载记录的 createdDirs 可能缺失（老记录、恢复过的任务）：产物顶层文件夹
     * 仍要进清理候选，否则文件删掉后 /Download/Temp 会一直空着。
     * 目标根目录（用户选的下载位置）不在候选里。
     */
    @Test
    fun `下载记录缺少 createdDirs 时顶层产物文件夹仍进清理候选`() {
        val dest = folder.newFolder("MFBDlTop")
        val dir = File(dest, "Avatar").apply { mkdirs() }
        val file = File(dir, "a.jpg").apply { writeBytes(ByteArray(10)) }
        val task = downloadTask(
            target = file.absolutePath,
            to = dest.absolutePath,
            children = listOf(
                TransferChild(key = "Avatar/a.jpg", name = "Avatar/a.jpg", path = "/r/a.jpg",
                    status = TransferChildStatus.SUCCESS, target = file.absolutePath)
            )
        )
        val dirs = downloadCleanupPaths(task).dirs.map { it.absolutePath }
        assertTrue(
            "dirs=$dirs dir=${dir.absolutePath} isDir=${dir.isDirectory} to=${task.to}",
            dirs.contains(dir.absolutePath)
        )
        assertTrue(!dirs.contains(dest.absolutePath))
    }

    /**
     * 恢复下载曾经把已存在的文件改名成「(1)」，原名那份半截就此失联。
     * 删任务时必须连原名一起清，否则用户看到「记录删了、文件还在」。
     */
    @Test
    fun `改名(1)后原名的半截文件一并清理`() {
        val dir = folder.newFolder("dl1")
        val stale = File(dir, "movie.mp4").apply { writeBytes(ByteArray(120)) }
        val renamed = File(dir, "movie (1).mp4").apply { writeBytes(ByteArray(40)) }
        val task = TransferTask(
            id = "t2", mode = TransferMode.DOWNLOAD, title = "movie", detail = "", total = 1,
            target = renamed.absolutePath, totalBytes = 4096,
            children = listOf(
                TransferChild(
                    key = "movie.mp4", name = "movie.mp4", path = "/r/movie.mp4",
                    status = TransferChildStatus.PAUSED,
                    target = renamed.absolutePath, sizeBytes = 4096
                )
            )
        )
        val names = downloadCleanupPaths(task).files.map { it.name }.toSet()
        assertEquals(setOf("movie.mp4", "movie (1).mp4"), names)
        assertTrue(stale.exists() && renamed.exists())
    }

    /**
     * 与记录大小一致的「原名」不是残留：那可能是用户自己的同名文件，
     * 当初正是为了不覆盖它才另存「(1)」。删任务时不能连它一起删。
     */
    @Test
    fun `原名大小与记录一致时不当作残留`() {
        val dir = folder.newFolder("dl2")
        val own = File(dir, "photo.jpg").apply { writeBytes(ByteArray(4096)) }
        val renamed = File(dir, "photo (1).jpg").apply { writeBytes(ByteArray(64)) }
        val task = TransferTask(
            id = "t3", mode = TransferMode.DOWNLOAD, title = "photo", detail = "", total = 1,
            target = renamed.absolutePath, totalBytes = 4096,
            children = listOf(
                TransferChild(
                    key = "photo.jpg", name = "photo.jpg", path = "/r/photo.jpg",
                    status = TransferChildStatus.SUCCESS,
                    target = renamed.absolutePath, sizeBytes = 4096
                )
            )
        )
        val names = downloadCleanupPaths(task).files.map { it.name }.toSet()
        assertEquals(setOf("photo (1).jpg"), names)
        assertTrue(own.exists())
    }

    /** 单文件记录（没有 children）同样要能按来源名找回被改名前的半截。 */
    @Test
    fun `单文件记录的改名残留按来源名清理`() {
        val dir = folder.newFolder("dl3")
        val stale = File(dir, "big.bin").apply { writeBytes(ByteArray(30)) }
        val renamed = File(dir, "big (1).bin").apply { writeBytes(ByteArray(10)) }
        val task = TransferTask(
            id = "t4", mode = TransferMode.DOWNLOAD, title = "big", detail = "", total = 1,
            target = renamed.absolutePath, totalBytes = 1024,
            sources = listOf("/r/big.bin")
        )
        val names = downloadCleanupPaths(task).files.map { it.name }.toSet()
        assertTrue(names.contains("big (1).bin"))
        assertTrue(names.contains("big.bin"))
        assertTrue(stale.exists())
    }

    /** 真正的用户文件即使大小与本次记录不同，也不能因为另存了 (1) 就被顺手删掉。 */
    @Test
    fun `标记了冲突原名时原名大小不同也保留`() {
        val dir = folder.newFolder("dl4")
        val own = File(dir, "doc.pdf").apply { writeBytes(ByteArray(100)) }
        val renamed = File(dir, "doc (1).pdf").apply { writeBytes(ByteArray(4096)) }
        val task = TransferTask(
            id = "t5", mode = TransferMode.DOWNLOAD, title = "doc", detail = "", total = 1,
            target = renamed.absolutePath, totalBytes = 4096,
            children = listOf(
                TransferChild(
                    key = "doc.pdf", name = "doc.pdf", path = "/r/doc.pdf",
                    status = TransferChildStatus.SUCCESS,
                    target = renamed.absolutePath, sizeBytes = 4096,
                    conflictOriginal = own.absolutePath
                )
            )
        )
        val names = downloadCleanupPaths(task).files.map { it.name }.toSet()
        assertEquals(setOf("doc (1).pdf"), names)
        assertTrue(own.exists())
    }

    /** 单文件同名同大小跳过时没有 children 可记 preExisting，靠任务标记保住用户文件。 */
    @Test
    fun `单文件同名跳过的目标文件不被清理`() {
        val dir = folder.newFolder("dl5")
        val own = File(dir, "keep.zip").apply { writeBytes(ByteArray(100)) }
        val task = TransferTask(
            id = "t6", mode = TransferMode.DOWNLOAD, title = "keep", detail = "", total = 1,
            target = own.absolutePath, totalBytes = 100,
            sources = listOf("/r/keep.zip"), targetPreExisting = true
        )
        val paths = downloadCleanupPaths(task)
        assertTrue(paths.files.isEmpty())
        assertEquals(setOf("keep.zip.part"), paths.parts.map { it.name }.toSet())
        assertTrue(own.exists())
    }

    private fun uploadTask(
        to: String,
        target: String = "",
        uploaded: List<String>
    ): TransferTask = TransferTask(
        id = "u2", mode = TransferMode.UPLOAD, title = "Folder", detail = "", total = uploaded.size,
        to = to, target = target, uploadedTargets = uploaded.toSet()
    )

    /** 文件夹上传会建出多级远端目录：删文件后这些空壳要由深到浅回收，目标根不动。 */
    @Test
    fun `上传任务的远端目录由深到浅且不含目标根`() {
        val dirs = uploadDirCleanupPaths(
            uploadTask(
                to = "/photo",
                uploaded = listOf("/photo/Folder/sub/a.txt", "/photo/Folder/b.txt")
            )
        )
        assertEquals(listOf("/photo/Folder/sub", "/photo/Folder"), dirs)
    }

    @Test
    fun `上传到远端根时只回收本次建出来的文件夹`() {
        val dirs = uploadDirCleanupPaths(
            uploadTask(to = "/", uploaded = listOf("/Folder/sub/a.txt"))
        )
        assertEquals(listOf("/Folder/sub", "/Folder"), dirs)
    }

    /** 前缀相同的兄弟目录不能被误判成子目录（/photo 与 /photography）。 */
    @Test
    fun `名字前缀相同的目录不算子目录`() {
        val dirs = uploadDirCleanupPaths(
            uploadTask(to = "/photo", uploaded = listOf("/photography/a.txt"))
        )
        assertTrue(dirs.isEmpty())
    }

    /** 旧记录把目标写成标签名或空串时，退回用 target（本次上传的文件夹）当根。 */
    @Test
    fun `目标为空时按落地目录回收并包含自身`() {
        val dirs = uploadDirCleanupPaths(
            uploadTask(to = "群晖", target = "/photo/Folder", uploaded = listOf("/photo/Folder/sub/a.txt"))
        )
        assertEquals(listOf("/photo/Folder/sub", "/photo/Folder"), dirs)
    }

    @Test
    fun `下载任务不产生远端目录清理路径`() {
        val task = downloadTask(target = "/sdcard/Download", sources = listOf("/r/a.txt"))
        assertTrue(uploadDirCleanupPaths(task).isEmpty())
    }

    /**
     * 目录选择器回来的是不带前导 `/` 的相对路径，老记录就把它原样存进了 to。
     * 不补正的话 boundary 判空、`inside()` 恒不成立，空文件夹会一直留在远端。
     */
    @Test
    fun `目标路径是相对形式时仍能回收空目录`() {
        val dirs = uploadDirCleanupPaths(
            uploadTask(
                to = "Backup/Temp",
                uploaded = listOf("/Backup/Temp/cmp/a.txt", "/Backup/Temp/cmp/b.txt")
            )
        )
        assertEquals(listOf("/Backup/Temp/cmp"), dirs)
    }

    /** to 与 target 都缺（上传中/老记录）：按子项目标的公共父目录回收，含该目录自身。 */
    @Test
    fun `记录缺少落地路径时按子项目标还原目录`() {
        val task = TransferTask(
            id = "u12", mode = TransferMode.UPLOAD, title = "cmp", detail = "", total = 2,
            to = "", target = "",
            children = listOf(
                TransferChild(
                    key = "cmp/a.txt", name = "cmp/a.txt", path = "/local/a.txt",
                    target = "/Backup/Temp/cmp/a.txt"
                ),
                TransferChild(
                    key = "cmp/b.txt", name = "cmp/b.txt", path = "/local/b.txt",
                    target = "/Backup/Temp/cmp/b.txt"
                )
            )
        )
        assertEquals(listOf("/Backup/Temp/cmp"), uploadDirCleanupPaths(task))
    }

    /** 上传空文件夹：没有任何子项，target 本身就是那个目录，也必须能回收。 */
    @Test
    fun `上传空文件夹时把目标目录本身纳入回收`() {
        val task = TransferTask(
            id = "u13", mode = TransferMode.UPLOAD, title = "Empty", detail = "", total = 1,
            to = "/Backup/Temp", target = "/Backup/Temp/Empty"
        )
        assertEquals(listOf("/Backup/Temp/Empty"), uploadDirCleanupPaths(task))
    }

    /** 上传一个文件夹：点击记录落在文件夹本身，而不是夹内第一个文件。 */
    @Test
    fun `上传文件夹记录点击打开落在文件夹本身`() {
        val task = TransferTask(
            id = "u14", mode = TransferMode.UPLOAD, title = "cmp5", detail = "", total = 1,
            to = "/Backup/test", target = "/Backup/test/cmp5/inner/c.txt",
            folderTask = true, status = TransferStatus.SUCCESS,
            sourceItems = listOf("/sdcard/Download/cmp5"),
            uploadedTargets = setOf("/Backup/test/cmp5/inner/c.txt")
        )
        assertTrue(transferIsFolderTask(task))
        assertEquals("/Backup/test/cmp5", transferTargetPathOf(task))
    }

    /**
     * 详情里来源与目标共用一条规则：单条目给完整路径（含文件名 / 文件夹名），
     * 多选给目录。两端口径一致，用户才能一眼对上「从哪到哪」。
     */
    @Test
    fun `来源与目标在单条目时都带名字`() {
        val folder = TransferTask(
            id = "u15", mode = TransferMode.UPLOAD, title = "cmp5", detail = "", total = 1,
            to = "/Backup/test", target = "/Backup/test/cmp5/inner/c.txt",
            folderTask = true,
            sources = listOf("/sdcard/Download/cmp5")
        )
        assertEquals("/sdcard/Download/cmp5", transferSourcePathOf(folder))
        assertEquals("/Backup/test/cmp5", transferTargetPathOf(folder))
        assertEquals(listOf("/sdcard/Download/cmp5"), transferSourcePathsOf(folder))
        assertEquals(listOf("/Backup/test/cmp5"), transferTargetPathsOf(folder))

        val single = TransferTask(
            id = "u16", mode = TransferMode.UPLOAD, title = "a.txt", detail = "", total = 1,
            to = "/Backup/test", target = "/Backup/test/a.txt",
            sources = listOf("/sdcard/Download/a.txt")
        )
        assertEquals("/sdcard/Download/a.txt", transferSourcePathOf(single))
        assertEquals("/Backup/test/a.txt", transferTargetPathOf(single))

        // 下载单文件：目标也要带文件名（不能只剩下载目录），与来源对称。
        val dl = TransferTask(
            id = "u16b", mode = TransferMode.DOWNLOAD, title = "b.txt", detail = "", total = 1,
            to = "/sdcard/Download", target = "/sdcard/Download/b.txt",
            sources = listOf("/Backup/test/b.txt")
        )
        assertEquals("/Backup/test/b.txt", transferSourcePathOf(dl))
        assertEquals("/sdcard/Download/b.txt", transferTargetPathOf(dl))

        // 老记录里的远端源路径是相对形式：统一补成绝对，与目标同口径。
        val rel = TransferTask(
            id = "u16c", mode = TransferMode.DOWNLOAD, title = "x.txt", detail = "", total = 1,
            to = "/sdcard/Download", target = "/sdcard/Download/x.txt",
            sources = listOf("Backup/Temp/x.txt")
        )
        assertEquals("/Backup/Temp/x.txt", transferSourcePathOf(rel))
    }

    @Test
    fun `多选时来源与目标逐条展示且各带名字`() {
        val task = TransferTask(
            id = "u17", mode = TransferMode.UPLOAD, title = "cmp5", detail = "", total = 3,
            to = "/Backup/test",
            target = "/Backup/test/cmp5/inner/c.txt",
            sources = listOf(
                "/sdcard/Download/cmp5/a.txt",
                "/sdcard/Download/cmp5/b.txt",
                "/sdcard/Download/cmp5/inner/c.txt"
            )
        )
        // 选中的文件按文件、文件夹按文件夹，各给完整路径，不再折叠成父目录。
        assertEquals(
            listOf(
                "/sdcard/Download/cmp5/a.txt",
                "/sdcard/Download/cmp5/b.txt",
                "/sdcard/Download/cmp5/inner/c.txt"
            ),
            transferSourcePathsOf(task)
        )
        // 目标是「目标目录 + 各自名字」，与来源一一对应。
        assertEquals(
            listOf("/Backup/test/a.txt", "/Backup/test/b.txt", "/Backup/test/c.txt"),
            transferTargetPathsOf(task)
        )
    }

    /**
     * 上传一个文件夹：来源与目标都只给文件夹本身。
     * `sources` 记的是夹内每个文件（重试要用），展示必须按顶层条目走，
     * 否则详情里会把文件夹里的文件一条条列出来。
     */
    @Test
    fun `上传文件夹的详情只给文件夹本身`() {
        val task = TransferTask(
            id = "u18", mode = TransferMode.UPLOAD, title = "Temp", detail = "", total = 3,
            to = "/Backup", target = "/Backup/Temp",
            folderTask = true,
            sources = listOf(
                "/storage/emulated/0/Download/Temp/a.apk",
                "/storage/emulated/0/Download/Temp/b.apk"
            ),
            sourceItems = listOf("/storage/emulated/0/Download/Temp")
        )
        assertEquals(listOf("/storage/emulated/0/Download/Temp"), transferSourcePathsOf(task))
        assertEquals(listOf("/Backup/Temp"), transferTargetPathsOf(task))
        assertEquals("/Backup/Temp", transferTargetPathOf(task))
    }

    /** 老记录没有 sourceItems：夹内文件同属一个与标题同名的目录时，折回那个目录。 */
    @Test
    fun `老记录里的夹内文件折回文件夹`() {
        val old = TransferTask(
            id = "u19", mode = TransferMode.UPLOAD, title = "Temp", detail = "", total = 3,
            to = "/Backup", target = "/Backup/Temp", folderTask = true,
            sources = listOf(
                "/sdcard/Download/Temp/a.apk",
                "/sdcard/Download/Temp/b.apk"
            )
        )
        assertEquals(listOf("/sdcard/Download/Temp"), transferSourcePathsOf(old))
        assertEquals(listOf("/Backup/Temp"), transferTargetPathsOf(old))
    }

    /**
     * 两个同名文件夹记录：打开位置各自由自己的 `to` 决定，
     * 不能靠「子项公共父目录」反推（那会把两条记录串到同一个 Temp 上）。
     */
    @Test
    fun `同名文件夹按各自的记录定位`() {
        val a = TransferTask(
            id = "t1", mode = TransferMode.UPLOAD, title = "Temp", detail = "", total = 1,
            to = "/Backup/test", target = "/Backup/test/Temp/x.txt", folderTask = true,
            sourceItems = listOf("/sdcard/Download/Temp")
        )
        val b = TransferTask(
            id = "t2", mode = TransferMode.UPLOAD, title = "Temp", detail = "", total = 1,
            to = "/Backup/photo", target = "/Backup/photo/Temp/inner/y.txt", folderTask = true,
            sourceItems = listOf("/sdcard/Documents/Temp")
        )
        assertEquals("/Backup/test/Temp", transferTargetPathOf(a))
        assertEquals("/Backup/photo/Temp", transferTargetPathOf(b))
    }

    /** 下载文件夹：产物 = 下载目录 + 文件夹名，来源就是那个远端文件夹。 */
    @Test
    fun `下载文件夹的产物路径是目标目录下的文件夹`() {
        val dl = TransferTask(
            id = "d1", mode = TransferMode.DOWNLOAD, title = "Temp", detail = "", total = 3,
            to = "/sdcard/Download", target = "/sdcard/Download", folderTask = true,
            sourceItems = listOf("/Backup/Temp")
        )
        assertEquals("/sdcard/Download/Temp", transferTargetPathOf(dl))
        assertEquals(listOf("/sdcard/Download/Temp"), transferTargetPathsOf(dl))
        assertEquals(listOf("/Backup/Temp"), transferSourcePathsOf(dl))
    }
}
