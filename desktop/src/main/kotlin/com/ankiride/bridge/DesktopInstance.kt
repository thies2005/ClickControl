package com.ankiride.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService

internal class DesktopInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val watcher: WatchService
) : AutoCloseable {
    suspend fun awaitReopen(): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            var reopen = false
            var valid = true
            while (valid && !reopen) {
                val key = watcher.take()
                reopen = key.pollEvents().any { event ->
                    event.kind() == OVERFLOW || event.context().toString() == REOPEN_FILE
                }
                valid = key.reset()
            }
            reopen
        } catch (_: ClosedWatchServiceException) {
            false
        }
    }

    override fun close() {
        try {
            watcher.close()
        } finally {
            try {
                if (lock.isValid) lock.release()
            } finally {
                channel.close()
            }
        }
    }

    companion object {
        private const val REOPEN_FILE = "desktop-reopen"

        // A secondary launch wakes the current instance without starting another BLE worker.
        fun open(directory: Path): DesktopInstance? {
            Files.createDirectories(directory)
            val watcher = directory.fileSystem.newWatchService()
            try {
                directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
                val channel = FileChannel.open(directory.resolve("desktop.lock"), CREATE, WRITE)
                try {
                    val lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock != null) return DesktopInstance(channel, lock, watcher)
                    Files.write(directory.resolve(REOPEN_FILE), byteArrayOf(1))
                } catch (e: Exception) {
                    channel.close()
                    throw e
                }
                channel.close()
            } catch (e: Exception) {
                watcher.close()
                throw e
            }
            watcher.close()
            return null
        }
    }
}
