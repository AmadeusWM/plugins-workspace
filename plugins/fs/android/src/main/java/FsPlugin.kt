// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package com.plugin.fs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.AssetManager.ACCESS_BUFFER
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

@TauriPlugin
class FsPlugin(private val activity: Activity): Plugin(activity) {
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
}
