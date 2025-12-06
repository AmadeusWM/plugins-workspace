// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package com.plugin.fs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.AssetManager.ACCESS_BUFFER
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import java.util.concurrent.Executors
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask

@InvokeArg
class WriteTextFileArgs {
  val uri: String = ""
  val content: String = ""
}

@InvokeArg
class GetFileDescriptorArgs {
    lateinit var uri: String
    lateinit var mode: String
}

@InvokeArg
class ReadDirectoryArgs {
    lateinit var uri: String
}

// ===== SAF (Storage Access Framework) InvokeArgs =====

@InvokeArg
class SafReadDirArgs {
    lateinit var baseUri: String
    var path: String = ""
}

@InvokeArg
class SafReadFileArgs {
    lateinit var baseUri: String
    var path: String = ""
}

@InvokeArg
class SafWriteFileArgs {
    lateinit var baseUri: String
    var path: String = ""
    lateinit var data: String // Base64 encoded
    var append: Boolean = false
    var create: Boolean = true
}

@InvokeArg
class SafCreateFileArgs {
    lateinit var baseUri: String
    var path: String = ""
}

@InvokeArg
class SafMkdirArgs {
    lateinit var baseUri: String
    var path: String = ""
    var recursive: Boolean = false
}

@InvokeArg
class SafRemoveArgs {
    lateinit var baseUri: String
    var path: String = ""
}

@InvokeArg
class SafRenameArgs {
    lateinit var baseUri: String
    var oldPath: String = ""
    var newPath: String = ""
}

@InvokeArg
class SafCopyFileArgs {
    lateinit var baseUri: String
    var fromPath: String = ""
    var toPath: String = ""
}

@InvokeArg
class SafStatArgs {
    lateinit var baseUri: String
    var path: String = ""
}

@InvokeArg
class SafExistsArgs {
    lateinit var baseUri: String
    var path: String = ""
}

@InvokeArg
class SafWatchArgs {
    lateinit var baseUri: String
    var path: String = ""
    var recursive: Boolean = false
    lateinit var onEvent: Channel
}

@InvokeArg
class SafUnwatchArgs {
    var watcherId: Int = 0
}

@TauriPlugin
class FsPlugin(private val activity: Activity): Plugin(activity) {
    
    // ExecutorService for background I/O operations
    private val ioExecutor = Executors.newCachedThreadPool()
    
    // ===== SAF Helper Functions =====
    
    /**
     * Resolves a relative path against a tree URI to get the document URI
     */
    private fun resolveDocumentUri(treeUri: Uri, relativePath: String): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val normalizedPath = relativePath.trimStart('/').ifEmpty { "" }
        
        val documentId = if (normalizedPath.isEmpty()) {
            treeDocumentId
        } else {
            "$treeDocumentId/$normalizedPath"
        }
        
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }
    
    /**
     * Resolves a path for querying children (for readDir operations)
     */
    private fun resolveChildDocumentsUri(treeUri: Uri, relativePath: String): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val normalizedPath = relativePath.trimStart('/').ifEmpty { "" }
        
        val parentDocumentId = if (normalizedPath.isEmpty()) {
            treeDocumentId
        } else {
            "$treeDocumentId/$normalizedPath"
        }
        
        return DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    }
    
    /**
     * Get MIME type from filename
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }
    
    /**
     * Navigate to a document using DocumentFile, creating directories as needed if recursive is true
     */
    private fun navigateToDocument(treeUri: Uri, path: String, createDirs: Boolean = false): DocumentFile? {
        val rootDoc = DocumentFile.fromTreeUri(activity, treeUri) ?: return null
        
        if (path.isEmpty() || path == "/") {
            return rootDoc
        }
        
        val pathParts = path.trimStart('/').split("/")
        var currentDoc = rootDoc
        
        for ((index, part) in pathParts.withIndex()) {
            val isLastPart = index == pathParts.lastIndex
            val nextDoc = currentDoc.findFile(part)
            
            if (nextDoc != null) {
                currentDoc = nextDoc
            } else if (createDirs && !isLastPart) {
                // Create intermediate directory
                currentDoc = currentDoc.createDirectory(part) ?: return null
            } else {
                return null
            }
        }
        
        return currentDoc
    }
    
    /**
     * Navigate to parent document and get filename
     */
    private fun getParentAndFilename(treeUri: Uri, path: String): Pair<DocumentFile, String>? {
        val rootDoc = DocumentFile.fromTreeUri(activity, treeUri) ?: return null
        val normalizedPath = path.trimStart('/')
        
        if (normalizedPath.isEmpty()) {
            return null
        }
        
        val pathParts = normalizedPath.split("/")
        val fileName = pathParts.last()
        val parentPath = pathParts.dropLast(1).joinToString("/")
        
        val parentDoc = if (parentPath.isEmpty()) {
            rootDoc
        } else {
            navigateToDocument(treeUri, parentPath) ?: return null
        }
        
        return Pair(parentDoc, fileName)
    }

    // ===== Original Commands =====

    @SuppressLint("Recycle")
    @Command
    fun getFileDescriptor(invoke: Invoke) {
        val args = invoke.parseArgs(GetFileDescriptorArgs::class.java)

        val res = JSObject()

        if (args.uri.startsWith(app.tauri.TAURI_ASSETS_DIRECTORY_URI)) {
            val path = args.uri.substring(app.tauri.TAURI_ASSETS_DIRECTORY_URI.length)
            try {
                val fd = activity.assets.openFd(path).parcelFileDescriptor?.detachFd()
                res.put("fd", fd)
            } catch (e: IOException) {
                // if the asset is compressed, we cannot open a file descriptor directly
                // so we copy it to the cache and get a fd from there
                // this is a lot faster than serializing the file and sending it as invoke response
                // because on the Rust side we can leverage the custom protocol IPC and read the file directly
                val cacheFile = File(activity.cacheDir, "_assets/$path")
                cacheFile.parentFile?.mkdirs()
                copyAsset(path, cacheFile)

                val fd = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.parseMode(args.mode)).detachFd()
                res.put("fd", fd)
            }
        } else {
            val fd = activity.contentResolver.openAssetFileDescriptor(
                Uri.parse(args.uri),
                args.mode
            )?.parcelFileDescriptor?.detachFd()
            res.put("fd", fd)
        }

        invoke.resolve(res)
    }

    @Command
    fun readDir(invoke: Invoke) {
        val args = invoke.parseArgs(ReadDirectoryArgs::class.java)
        val contentResolver = this.activity.contentResolver

        val uri = args.uri.toUri()

        val tree = DocumentFile.fromTreeUri(this.activity, uri);

        val res = JSObject()
        val children = JSONArray()
        tree?.listFiles()?.forEach { file ->
            val child = JSObject()
            child.put("name", file.name)
            child.put("isDirectory", file.isDirectory)
            child.put("isFile", file.isFile)
            children.put(child)
        }

        res.put("entries", children)
        invoke.resolve(res)
    }

    @Throws(IOException::class)
    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(1024)
        var len: Int
        while ((input.read(buf).also { len = it }) > 0) {
            output.write(buf, 0, len)
        }
    }

    @Throws(IOException::class)
    private fun copyAsset(assetPath: String, cacheFile: File) {
        val input = activity.assets.open(assetPath, ACCESS_BUFFER)
        input.use { i ->
            val output = FileOutputStream(cacheFile, false)
            output.use { o ->
                copy(i, o)
            }
        }
    }
    
    // ===== SAF Commands =====
    
    @Command
    fun safReadDir(invoke: Invoke) {
        val args = invoke.parseArgs(SafReadDirArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        ioExecutor.execute {
            try {
                val childrenUri = resolveChildDocumentsUri(treeUri, args.path)
                
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                
                val cursor = activity.contentResolver.query(
                    childrenUri,
                    projection,
                    null,
                    null,
                    null
                )
                
                val entries = JSONArray()
                cursor?.use {
                    while (it.moveToNext()) {
                        val entry = JSObject()
                        val displayName = it.getString(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        )
                        val mimeType = it.getString(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        )
                        
                        entry.put("name", displayName)
                        entry.put("isDirectory", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
                        entry.put("isFile", mimeType != DocumentsContract.Document.MIME_TYPE_DIR)
                        entry.put("isSymlink", false)
                        
                        entries.put(entry)
                    }
                }
                
                val res = JSObject()
                res.put("entries", entries)
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject("Failed to read directory: ${e.message}")
            }
        }
    }
    
    @Command
    fun safReadFile(invoke: Invoke) {
        val args = invoke.parseArgs(SafReadFileArgs::class.java)
        val treeUri = args.baseUri.toUri()
        val documentUri = resolveDocumentUri(treeUri, args.path)
        
        // Run I/O operations on a background thread to avoid blocking the main thread
        ioExecutor.execute {
            try {
                val inputStream = activity.contentResolver.openInputStream(documentUri)
                inputStream?.use { stream ->
                    val bytes = stream.readBytes()
                    val res = JSObject()
                    res.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    invoke.resolve(res)
                } ?: invoke.reject("Failed to open input stream")
            } catch (e: Exception) {
                invoke.reject("Failed to read file: ${e.message}")
            }
        }
    }
    
    @Command
    fun safWriteFile(invoke: Invoke) {
        val args = invoke.parseArgs(SafWriteFileArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        // Run I/O operations on a background thread to avoid blocking the main thread
        ioExecutor.execute {
            try {
                // Try to find existing file first
                val existingDoc = navigateToDocument(treeUri, args.path)
                
                if (existingDoc != null && existingDoc.isFile) {
                    // File exists, write to it
                    val mode = if (args.append) "wa" else "wt"
                    val outputStream = activity.contentResolver.openOutputStream(existingDoc.uri, mode)
                    outputStream?.use { stream ->
                        val data = Base64.decode(args.data, Base64.NO_WRAP)
                        stream.write(data)
                        invoke.resolve(JSObject())
                    } ?: invoke.reject("Failed to open output stream")
                } else if (args.create) {
                    // File doesn't exist, create it
                    val (parentDoc, fileName) = getParentAndFilename(treeUri, args.path)
                        ?: return@execute invoke.reject("Invalid path")
                    
                    val mimeType = getMimeType(fileName)
                    val newDoc = parentDoc.createFile(mimeType, fileName)
                        ?: return@execute invoke.reject("Failed to create file")
                    
                    val outputStream = activity.contentResolver.openOutputStream(newDoc.uri, "wt")
                    outputStream?.use { stream ->
                        val data = Base64.decode(args.data, Base64.NO_WRAP)
                        stream.write(data)
                        invoke.resolve(JSObject())
                    } ?: invoke.reject("Failed to open output stream for new file")
                } else {
                    invoke.reject("File not found and create is false")
                }
            } catch (e: Exception) {
                invoke.reject("Failed to write file: ${e.message}")
            }
        }
    }
    
    @Command
    fun safCreateFile(invoke: Invoke) {
        val args = invoke.parseArgs(SafCreateFileArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        ioExecutor.execute {
            try {
                val (parentDoc, fileName) = getParentAndFilename(treeUri, args.path)
                    ?: return@execute invoke.reject("Invalid path")
                
                val mimeType = getMimeType(fileName)
                val newDoc = parentDoc.createFile(mimeType, fileName)
                
                val res = JSObject()
                res.put("uri", newDoc?.uri?.toString())
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject("Failed to create file: ${e.message}")
            }
        }
    }
    
    @Command
    fun safMkdir(invoke: Invoke) {
        val args = invoke.parseArgs(SafMkdirArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        ioExecutor.execute {
            try {
                val rootDoc = DocumentFile.fromTreeUri(activity, treeUri)
                    ?: return@execute invoke.reject("Invalid tree URI")
                
                val pathParts = args.path.trimStart('/').split("/")
                var currentDoc = rootDoc
                
                for (part in pathParts) {
                    val existingDir = currentDoc.findFile(part)
                    if (existingDir != null) {
                        if (existingDir.isDirectory) {
                            currentDoc = existingDir
                        } else {
                            return@execute invoke.reject("Path component '$part' exists but is not a directory")
                        }
                    } else {
                        val newDir = currentDoc.createDirectory(part)
                            ?: return@execute invoke.reject("Failed to create directory '$part'")
                        currentDoc = newDir
                    }
                    
                    // If not recursive, only create the final directory
                    if (!args.recursive && part != pathParts.last()) {
                        return@execute invoke.reject("Parent directory does not exist and recursive is false")
                    }
                }
                
                val res = JSObject()
                res.put("uri", currentDoc.uri.toString())
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject("Failed to create directory: ${e.message}")
            }
        }
    }
    
    @Command
    fun safRemove(invoke: Invoke) {
        val args = invoke.parseArgs(SafRemoveArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        ioExecutor.execute {
            try {
                val documentUri = resolveDocumentUri(treeUri, args.path)
                val deleted = DocumentsContract.deleteDocument(activity.contentResolver, documentUri)
                
                if (deleted) {
                    invoke.resolve(JSObject())
                } else {
                    invoke.reject("Failed to delete document")
                }
            } catch (e: Exception) {
                invoke.reject("Failed to delete: ${e.message}")
            }
        }
    }
    
    @Command
    fun safRename(invoke: Invoke) {
        val args = invoke.parseArgs(SafRenameArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        ioExecutor.execute {
            try {
                val sourceUri = resolveDocumentUri(treeUri, args.oldPath)
                val newName = args.newPath.trimStart('/').split("/").last()
                
                val newUri = DocumentsContract.renameDocument(
                    activity.contentResolver,
                    sourceUri,
                    newName
                )
                
                val res = JSObject()
                res.put("uri", newUri?.toString())
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject("Failed to rename: ${e.message}")
            }
        }
    }
    
    @Command
    fun safCopyFile(invoke: Invoke) {
        val args = invoke.parseArgs(SafCopyFileArgs::class.java)
        val treeUri = args.baseUri.toUri()
        
        ioExecutor.execute {
            try {
                val sourceUri = resolveDocumentUri(treeUri, args.fromPath)
                
                // Get target parent directory
                val (targetParentDoc, targetFileName) = getParentAndFilename(treeUri, args.toPath)
                    ?: return@execute invoke.reject("Invalid target path")
                
                // Read source file
                val inputStream = activity.contentResolver.openInputStream(sourceUri)
                    ?: return@execute invoke.reject("Failed to open source file")
                
                // Create target file
                val mimeType = getMimeType(targetFileName)
                val targetDoc = targetParentDoc.createFile(mimeType, targetFileName)
                    ?: return@execute invoke.reject("Failed to create target file")
                
                // Write to target
                val outputStream = activity.contentResolver.openOutputStream(targetDoc.uri)
                    ?: return@execute invoke.reject("Failed to open target file for writing")
                
                inputStream.use { input ->
                    outputStream.use { output ->
                        copy(input, output)
                    }
                }
                
                val res = JSObject()
                res.put("uri", targetDoc.uri.toString())
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject("Failed to copy: ${e.message}")
            }
        }
    }
    
    @Command
    fun safStat(invoke: Invoke) {
        val args = invoke.parseArgs(SafStatArgs::class.java)
        val treeUri = args.baseUri.toUri()
        val documentUri = resolveDocumentUri(treeUri, args.path)
        
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        
        ioExecutor.execute {
            try {
                val cursor = activity.contentResolver.query(
                    documentUri,
                    projection,
                    null,
                    null,
                    null
                )
                
                cursor?.use {
                    if (it.moveToFirst()) {
                        val res = JSObject()
                        val mimeType = it.getString(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        )
                        
                        res.put("isFile", mimeType != DocumentsContract.Document.MIME_TYPE_DIR)
                        res.put("isDirectory", mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
                        res.put("isSymlink", false)
                        res.put("size", it.getLong(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                        ))
                        res.put("mtime", it.getLong(
                            it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        ))
                        res.put("atime", JSONObject.NULL)
                        res.put("birthtime", JSONObject.NULL)
                        res.put("readonly", false)
                        res.put("fileAttributes", JSONObject.NULL)
                        res.put("dev", JSONObject.NULL)
                        res.put("ino", JSONObject.NULL)
                        res.put("mode", JSONObject.NULL)
                        res.put("nlink", JSONObject.NULL)
                        res.put("uid", JSONObject.NULL)
                        res.put("gid", JSONObject.NULL)
                        res.put("rdev", JSONObject.NULL)
                        res.put("blksize", JSONObject.NULL)
                        res.put("blocks", JSONObject.NULL)
                        
                        invoke.resolve(res)
                    } else {
                        invoke.reject("File not found")
                    }
                } ?: invoke.reject("Failed to query file info")
            } catch (e: Exception) {
                invoke.reject("Failed to stat file: ${e.message}")
            }
        }
    }
    
    @Command
    fun safExists(invoke: Invoke) {
        val args = invoke.parseArgs(SafExistsArgs::class.java)
        val treeUri = args.baseUri.toUri()
        val documentUri = resolveDocumentUri(treeUri, args.path)
        
        ioExecutor.execute {
            try {
                val cursor = activity.contentResolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null
                )
                
                val exists = cursor?.use { it.moveToFirst() } ?: false
                
                val res = JSObject()
                res.put("exists", exists)
                invoke.resolve(res)
            } catch (e: Exception) {
                // Document doesn't exist or access denied
                val res = JSObject()
                res.put("exists", false)
                invoke.resolve(res)
            }
        }
    }
    
    // ===== File Watching =====
    
    /**
     * Data class to store file/directory state for comparison
     */
    private data class FileState(
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long
    )
    
    /**
     * Data class to hold watcher state
     */
    private data class WatcherState(
        val timer: Timer,
        val treeUri: Uri,
        val path: String,
        val recursive: Boolean,
        val channel: Channel,
        var lastSnapshot: Map<String, FileState>
    )
    
    private val watchers = mutableMapOf<Int, WatcherState>()
    private var watcherId = 0
    private val POLL_INTERVAL_MS = 2000L // Poll every 2 seconds
    
    /**
     * Takes a snapshot of a directory's contents for comparison
     */
    private fun takeDirectorySnapshot(treeUri: Uri, path: String, recursive: Boolean): Map<String, FileState> {
        val snapshot = mutableMapOf<String, FileState>()
        
        // Normalize path - treat ".", "", and "/" as root
        val normalizedPath = path.trim().let { 
            if (it == "." || it == "/" || it.isEmpty()) "" else it.trimStart('/') 
        }
        
        
        try {
            val doc = if (normalizedPath.isEmpty()) {
                DocumentFile.fromTreeUri(activity, treeUri)
            } else {
                navigateToDocument(treeUri, normalizedPath)
            }
            
            if (doc == null) {
                android.util.Log.e("FsPlugin", "takeDirectorySnapshot: doc is null!")
                return snapshot
            }
            
            if (!doc.exists()) {
                android.util.Log.e("FsPlugin", "takeDirectorySnapshot: doc doesn't exist!")
                return snapshot
            }
            
            if (!doc.isDirectory) {
                android.util.Log.e("FsPlugin", "takeDirectorySnapshot: doc is not a directory! isFile=${doc.isFile}")
                return snapshot
            }
            
            
            scanDirectory(doc, "", recursive, snapshot)
        } catch (e: Exception) {
            android.util.Log.e("FsPlugin", "Error taking directory snapshot: ${e.message}", e)
        }
        
        return snapshot
    }
    
    /**
     * Recursively scans a directory and adds entries to the snapshot
     */
    private fun scanDirectory(doc: DocumentFile, basePath: String, recursive: Boolean, snapshot: MutableMap<String, FileState>) {
        val files = doc.listFiles()
        
        files.forEach { child ->
            val childPath = if (basePath.isEmpty()) child.name ?: "" else "$basePath/${child.name ?: ""}"
            val lastMod = child.lastModified()
            val size = child.length()
            
            
            snapshot[childPath] = FileState(
                name = child.name ?: "",
                isDirectory = child.isDirectory,
                lastModified = lastMod,
                size = size
            )
            
            if (recursive && child.isDirectory) {
                scanDirectory(child, childPath, true, snapshot)
            }
        }
    }
    
    /**
     * Compares two snapshots and emits change events
     */
    private fun compareSnapshots(
        watcherId: Int,
        oldSnapshot: Map<String, FileState>,
        newSnapshot: Map<String, FileState>,
        treeUri: Uri,
        basePath: String,
        channel: Channel
    ) {
        
        val handler = Handler(Looper.getMainLooper())
        
        // Check for created files (in new but not in old)
        for ((path, state) in newSnapshot) {
            if (path !in oldSnapshot) {
                handler.post {
                    val event = JSObject()
                    event.put("watcherId", watcherId)
                    event.put("uri", buildDocumentUri(treeUri, basePath, path))
                    event.put("selfChange", false)
                    event.put("type", "create")
                    event.put("path", path)
                    channel.send(event)
                }
            }
        }
        
        // Check for removed files (in old but not in new)
        for ((path, state) in oldSnapshot) {
            if (path !in newSnapshot) {
                handler.post {
                    val event = JSObject()
                    event.put("watcherId", watcherId)
                    event.put("uri", buildDocumentUri(treeUri, basePath, path))
                    event.put("selfChange", false)
                    event.put("type", "remove")
                    event.put("path", path)
                    channel.send(event)
                }
            }
        }
        
        // Check for modified files (in both but changed)
        for ((path, newState) in newSnapshot) {
            val oldState = oldSnapshot[path]
            if (oldState != null && (oldState.lastModified != newState.lastModified || oldState.size != newState.size)) {
                handler.post {
                    val event = JSObject()
                    event.put("watcherId", watcherId)
                    event.put("uri", buildDocumentUri(treeUri, basePath, path))
                    event.put("selfChange", false)
                    event.put("type", "modify")
                    event.put("path", path)
                    channel.send(event)
                }
            }
        }
    }
    
    /**
     * Builds a document URI string for a given path
     */
    private fun buildDocumentUri(treeUri: Uri, basePath: String, relativePath: String): String {
        val fullPath = if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"
        return resolveDocumentUri(treeUri, fullPath).toString()
    }
    
    @Command
    fun safWatch(invoke: Invoke) {
        val args = invoke.parseArgs(SafWatchArgs::class.java)
        
        val treeUri = args.baseUri.toUri()
        val documentUri = resolveDocumentUri(treeUri, args.path)
        
        val id = watcherId++
        
        // Take initial snapshot on a background thread to avoid blocking
        ioExecutor.execute {
            try {
                val initialSnapshot = takeDirectorySnapshot(treeUri, args.path, args.recursive)
                
                // Create timer for polling (runs on its own daemon thread)
                val timer = Timer("SafWatcher-$id", true)
                
                val watcherState = WatcherState(
                    timer = timer,
                    treeUri = treeUri,
                    path = args.path,
                    recursive = args.recursive,
                    channel = args.onEvent,
                    lastSnapshot = initialSnapshot
                )
                
                watchers[id] = watcherState
                
                // Schedule polling task (already runs on Timer's background thread)
                timer.scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        try {
                            val currentWatcher = watchers[id] ?: return
                            
                            val newSnapshot = takeDirectorySnapshot(currentWatcher.treeUri, currentWatcher.path, currentWatcher.recursive)
                            
                            if (newSnapshot != currentWatcher.lastSnapshot) {
                                compareSnapshots(id, currentWatcher.lastSnapshot, newSnapshot, currentWatcher.treeUri, currentWatcher.path, currentWatcher.channel)
                                
                                // Update the snapshot
                                watchers[id] = currentWatcher.copy(lastSnapshot = newSnapshot)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("FsPlugin", "Error in safWatch polling: ${e.message}")
                        }
                    }
                }, POLL_INTERVAL_MS, POLL_INTERVAL_MS)
                
                val res = JSObject()
                res.put("watcherId", id)
                invoke.resolve(res)
            } catch (e: Exception) {
                invoke.reject("Failed to start watcher: ${e.message}")
            }
        }
    }
    
    @Command
    fun safUnwatch(invoke: Invoke) {
        val args = invoke.parseArgs(SafUnwatchArgs::class.java)
        
        watchers[args.watcherId]?.let { watcherState ->
            watcherState.timer.cancel()
            watchers.remove(args.watcherId)
            invoke.resolve(JSObject())
        } ?: invoke.reject("Watcher not found")
    }
}
