# Android Storage Access Framework (SAF) Wrapper Specification

## Overview

This specification describes how to extend the Tauri FS plugin to transparently support Android's Storage Access Framework (SAF) Content URIs. The goal is to allow frontend code to use standard path-like operations (e.g., `/nested1/nested2/document.txt`) while internally resolving these paths against a base Content URI tree obtained from the dialog plugin.

## Problem Statement

When using `dialog.open({ directory: true })` on Android, the returned value is a Content URI like:
```
content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FObsidian
```

This URI cannot be used with standard filesystem APIs. The Android SAF requires:
1. Building child document URIs using `DocumentsContract.buildDocumentUriUsingTree()`
2. Using `ContentResolver` for file operations instead of standard `java.io.File`
3. Querying document metadata via cursors rather than `stat()`

## Proposed Solution

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Frontend (TypeScript)                       │
│  readFile("/nested/file.txt", { baseDir: contentUri })            │
└──────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Rust Commands Layer                           │
│  - Detect if baseDir is a Content URI (scheme = "content://")    │
│  - Route to Android-specific implementation                       │
└──────────────────────────────────────────────────────────────────┘
                                  │
                   ┌──────────────┴──────────────┐
                   ▼                              ▼
┌─────────────────────────────┐   ┌─────────────────────────────────┐
│    Desktop Implementation    │   │    Android Implementation        │
│    (Standard std::fs)        │   │    (JNI → Kotlin SAF Wrapper)   │
└─────────────────────────────┘   └─────────────────────────────────┘
```

### API Changes

#### 1. Extended `BaseDirectory` / `baseDir` Option

Currently, `baseDir` accepts a `BaseDirectory` enum (e.g., `AppData`, `Document`). We extend this to also accept a Content URI string on Android:

```typescript
// TypeScript
interface BaseOptions {
  /**
   * Base directory for the operation.
   * On desktop: BaseDirectory enum value
   * On Android: Can also be a Content URI string from dialog.open()
   */
  baseDir?: BaseDirectory | string;
}

// Example usage:
const rootUri = await dialog.open({ directory: true });
// rootUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FObsidian"

// Read a file relative to the selected directory
const content = await fs.readFile("/notes/daily.md", { baseDir: rootUri });

// List directory contents
const entries = await fs.readDir("/notes", { baseDir: rootUri });
```

#### 2. Rust Side Changes

```rust
// In commands.rs or a new android_saf.rs module

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BaseOptions {
    /// Either a BaseDirectory enum value or a Content URI string
    base_dir: Option<BaseDirectoryOrUri>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum BaseDirectoryOrUri {
    BaseDir(BaseDirectory),
    ContentUri(String),
}

impl BaseDirectoryOrUri {
    pub fn is_content_uri(&self) -> bool {
        match self {
            Self::ContentUri(uri) => uri.starts_with("content://"),
            _ => false,
        }
    }
}
```

### Android Kotlin Implementation

The Android plugin needs to be extended with new commands that wrap the SAF APIs:

#### 1. Core Helper Functions

```kotlin
// FsPlugin.kt

@TauriPlugin
class FsPlugin(private val activity: Activity): Plugin(activity) {
    
    /**
     * Resolves a relative path against a tree URI to get the document URI
     * 
     * @param treeUri The root tree URI (from ACTION_OPEN_DOCUMENT_TREE)
     * @param relativePath Path relative to the tree root (e.g., "/nested/file.txt")
     * @return The full document URI for the path
     */
    private fun resolveDocumentUri(treeUri: Uri, relativePath: String): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        
        // Normalize the path (remove leading slash, handle empty path)
        val normalizedPath = relativePath.trimStart('/').ifEmpty { "" }
        
        // Build the document ID by appending the path to the tree document ID
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
}
```

#### 2. File Operations

```kotlin
// Read file content
@Command
fun readFile(invoke: Invoke) {
    val args = invoke.parseArgs(ReadFileArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val documentUri = resolveDocumentUri(treeUri, args.path)
    
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

// Write file content
@Command
fun writeFile(invoke: Invoke) {
    val args = invoke.parseArgs(WriteFileArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val documentUri = resolveDocumentUri(treeUri, args.path)
    
    try {
        val outputStream = activity.contentResolver.openOutputStream(documentUri, "wt")
        outputStream?.use { stream ->
            val data = Base64.decode(args.data, Base64.NO_WRAP)
            stream.write(data)
            invoke.resolve()
        } ?: invoke.reject("Failed to open output stream")
    } catch (e: FileNotFoundException) {
        // File doesn't exist, need to create it
        createAndWriteFile(invoke, treeUri, args.path, args.data)
    } catch (e: Exception) {
        invoke.reject("Failed to write file: ${e.message}")
    }
}

// Create file
@Command
fun createFile(invoke: Invoke) {
    val args = invoke.parseArgs(CreateFileArgs::class.java)
    val treeUri = args.baseDir.toUri()
    
    // Split path into parent directory and filename
    val pathParts = args.path.trimStart('/').split("/")
    val fileName = pathParts.last()
    val parentPath = pathParts.dropLast(1).joinToString("/")
    
    val parentUri = resolveDocumentUri(treeUri, parentPath)
    
    try {
        val mimeType = getMimeType(fileName)
        val newDocUri = DocumentsContract.createDocument(
            activity.contentResolver,
            parentUri,
            mimeType,
            fileName
        )
        
        val res = JSObject()
        res.put("uri", newDocUri?.toString())
        invoke.resolve(res)
    } catch (e: Exception) {
        invoke.reject("Failed to create file: ${e.message}")
    }
}

// Create directory
@Command
fun mkdir(invoke: Invoke) {
    val args = invoke.parseArgs(MkdirArgs::class.java)
    val treeUri = args.baseDir.toUri()
    
    val pathParts = args.path.trimStart('/').split("/")
    val dirName = pathParts.last()
    val parentPath = pathParts.dropLast(1).joinToString("/")
    
    val parentUri = resolveDocumentUri(treeUri, parentPath)
    
    try {
        val newDirUri = DocumentsContract.createDocument(
            activity.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        )
        
        val res = JSObject()
        res.put("uri", newDirUri?.toString())
        invoke.resolve(res)
    } catch (e: Exception) {
        invoke.reject("Failed to create directory: ${e.message}")
    }
}
```

#### 3. Directory Listing

```kotlin
@Command
fun readDir(invoke: Invoke) {
    val args = invoke.parseArgs(ReadDirArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val childrenUri = resolveChildDocumentsUri(treeUri, args.path)
    
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    
    try {
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
```

#### 4. File Metadata (stat)

```kotlin
@Command
fun stat(invoke: Invoke) {
    val args = invoke.parseArgs(StatArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val documentUri = resolveDocumentUri(treeUri, args.path)
    
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    )
    
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
                res.put("atime", null) // Not available in SAF
                res.put("birthtime", null) // Not available in SAF
                
                invoke.resolve(res)
            } else {
                invoke.reject("File not found")
            }
        } ?: invoke.reject("Failed to query file info")
    } catch (e: Exception) {
        invoke.reject("Failed to stat file: ${e.message}")
    }
}
```

#### 5. File Operations (Delete, Rename, Move, Copy)

```kotlin
@Command
fun remove(invoke: Invoke) {
    val args = invoke.parseArgs(RemoveArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val documentUri = resolveDocumentUri(treeUri, args.path)
    
    try {
        val deleted = DocumentsContract.deleteDocument(activity.contentResolver, documentUri)
        if (deleted) {
            invoke.resolve()
        } else {
            invoke.reject("Failed to delete document")
        }
    } catch (e: Exception) {
        invoke.reject("Failed to delete: ${e.message}")
    }
}

@Command
fun rename(invoke: Invoke) {
    val args = invoke.parseArgs(RenameArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val sourceUri = resolveDocumentUri(treeUri, args.oldPath)
    
    // Extract new name from the new path
    val newName = args.newPath.trimStart('/').split("/").last()
    
    try {
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

@Command  
fun copyFile(invoke: Invoke) {
    val args = invoke.parseArgs(CopyFileArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val sourceUri = resolveDocumentUri(treeUri, args.fromPath)
    
    // Get target parent directory
    val targetPathParts = args.toPath.trimStart('/').split("/")
    val targetParentPath = targetPathParts.dropLast(1).joinToString("/")
    val targetParentUri = resolveDocumentUri(treeUri, targetParentPath)
    
    try {
        val copiedUri = DocumentsContract.copyDocument(
            activity.contentResolver,
            sourceUri,
            targetParentUri
        )
        
        val res = JSObject()
        res.put("uri", copiedUri?.toString())
        invoke.resolve(res)
    } catch (e: Exception) {
        invoke.reject("Failed to copy: ${e.message}")
    }
}
```

#### 6. File Watching

```kotlin
@InvokeArg
class WatchArgs {
    lateinit var baseDir: String
    lateinit var path: String
    var recursive: Boolean = false
}

private val contentObservers = mutableMapOf<Int, ContentObserver>()
private var watcherId = 0

@Command
fun watch(invoke: Invoke) {
    val args = invoke.parseArgs(WatchArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val documentUri = resolveDocumentUri(treeUri, args.path)
    
    val id = watcherId++
    
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            super.onChange(selfChange, uri, flags)
            
            val event = JSObject()
            event.put("watcherId", id)
            event.put("uri", uri?.toString())
            event.put("selfChange", selfChange)
            
            // Map Android flags to event types
            val eventType = when {
                flags and ContentResolver.NOTIFY_INSERT != 0 -> "create"
                flags and ContentResolver.NOTIFY_DELETE != 0 -> "remove"
                flags and ContentResolver.NOTIFY_UPDATE != 0 -> "modify"
                else -> "unknown"
            }
            event.put("type", eventType)
            
            // Emit event to frontend
            trigger("fs:watch", event)
        }
    }
    
    activity.contentResolver.registerContentObserver(
        documentUri,
        args.recursive,  // notifyForDescendants
        observer
    )
    
    contentObservers[id] = observer
    
    val res = JSObject()
    res.put("watcherId", id)
    invoke.resolve(res)
}

@Command
fun unwatch(invoke: Invoke) {
    val args = invoke.parseArgs(UnwatchArgs::class.java)
    
    contentObservers[args.watcherId]?.let { observer ->
        activity.contentResolver.unregisterContentObserver(observer)
        contentObservers.remove(args.watcherId)
        invoke.resolve()
    } ?: invoke.reject("Watcher not found")
}
```

#### 7. Check File Exists

```kotlin
@Command
fun exists(invoke: Invoke) {
    val args = invoke.parseArgs(ExistsArgs::class.java)
    val treeUri = args.baseDir.toUri()
    val documentUri = resolveDocumentUri(treeUri, args.path)
    
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
```

### URI Permission Persistence

When the dialog plugin returns a tree URI, the Android plugin should automatically persist the permission:

```kotlin
// In DialogPlugin.kt - folderPickerResult callback
@ActivityCallback
fun folderPickerResult(invoke: Invoke, result: ActivityResult) {
    when (result.resultCode) {
        Activity.RESULT_OK -> {
            val data = result.data
            val uri = data?.data
            
            if (uri != null) {
                // Persist permission across device reboots
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                               Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                activity.contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
            
            // ... rest of the callback
        }
    }
}
```

### Rust Integration

The Rust layer needs to detect when a Content URI is passed and route to the appropriate Android implementation:

```rust
// In commands.rs

#[tauri::command]
pub async fn read_file<R: Runtime>(
    webview: Webview<R>,
    global_scope: GlobalScope<Entry>,
    command_scope: CommandScope<Entry>,
    path: SafeFilePath,
    options: Option<BaseOptions>,
) -> CommandResult<tauri::ipc::Response> {
    let base_dir = options.as_ref().and_then(|o| o.base_dir.as_ref());
    
    // Check if this is an Android Content URI
    #[cfg(target_os = "android")]
    if let Some(BaseDirectoryOrUri::ContentUri(uri)) = base_dir {
        if uri.starts_with("content://") {
            return read_file_android_saf(&webview, uri, &path).await;
        }
    }
    
    // Fall back to standard implementation
    // ... existing code
}

#[cfg(target_os = "android")]
async fn read_file_android_saf<R: Runtime>(
    webview: &Webview<R>,
    base_uri: &str,
    path: &SafeFilePath,
) -> CommandResult<tauri::ipc::Response> {
    // Call the Android plugin
    let fs = webview.state::<crate::mobile::Fs<R>>();
    let result = fs.0.run_mobile_plugin::<ReadFileResponse>(
        "readFile",
        ReadFilePayload {
            base_dir: base_uri.to_string(),
            path: path.to_string(),
        },
    )?;
    
    Ok(tauri::ipc::Response::new(result.data))
}
```

## Supported Operations

| Operation | Desktop | Android SAF | Notes |
|-----------|---------|-------------|-------|
| `readFile` | ✅ | ✅ | Via `openInputStream` |
| `writeFile` | ✅ | ✅ | Via `openOutputStream` |
| `readTextFile` | ✅ | ✅ | Same as readFile + decode |
| `writeTextFile` | ✅ | ✅ | Same as writeFile |
| `readDir` | ✅ | ✅ | Via `buildChildDocumentsUriUsingTree` |
| `mkdir` | ✅ | ✅ | Via `createDocument` with DIR mime type |
| `create` | ✅ | ✅ | Via `createDocument` |
| `remove` | ✅ | ✅ | Via `deleteDocument` |
| `rename` | ✅ | ✅ | Via `renameDocument` |
| `copyFile` | ✅ | ✅ | Via `copyDocument` |
| `stat` | ✅ | ✅ (partial) | Some fields unavailable |
| `exists` | ✅ | ✅ | Via query |
| `watch` | ✅ | ✅ | Via `ContentObserver` |

### Limitations

1. **File metadata**: Android SAF provides limited metadata compared to desktop:
   - No access time (`atime`)
   - No creation time (`birthtime`) on most providers
   - File permissions not applicable

2. **Watch events**: ContentObserver provides less granular events:
   - Only INSERT, UPDATE, DELETE flags
   - No file rename detection (appears as DELETE + INSERT)
   - Provider-dependent: some providers may not emit all events

3. **Atomic operations**: Some operations that are atomic on desktop may not be on Android SAF

4. **Performance**: SAF operations have higher overhead than direct file access

## Migration Path

### For existing apps

Existing apps using the FS plugin with `BaseDirectory` enum will continue to work unchanged. The new Content URI support is additive.

### New Android-focused apps

```typescript
import { open } from '@tauri-apps/plugin-dialog';
import { readDir, readFile, writeFile, watch } from '@tauri-apps/plugin-fs';

// 1. Get user permission for a directory
const rootUri = await open({ directory: true });
if (!rootUri) return;

// 2. Use the URI as base for all operations
const files = await readDir('/documents', { baseDir: rootUri });

// 3. Read/write files
const content = await readFile('/documents/note.md', { baseDir: rootUri });
await writeFile('/documents/note.md', newContent, { baseDir: rootUri });

// 4. Watch for changes
const unwatch = await watch(
  '/documents',
  (event) => console.log('File changed:', event),
  { baseDir: rootUri, recursive: true }
);
```

## Security Considerations

1. **URI permissions**: Content URIs from `ACTION_OPEN_DOCUMENT_TREE` grant access only to the selected directory and its descendants. The app cannot access files outside this scope.

2. **Permission persistence**: Permissions should be persisted using `takePersistableUriPermission()` to survive app restarts and device reboots.

3. **Scope validation**: The Rust layer should validate that requested paths don't attempt path traversal attacks (e.g., `../..`).

## Implementation Phases

### Phase 1: Core Operations
- [ ] Extend `BaseOptions` to accept Content URI
- [ ] Implement `readFile`, `writeFile` for SAF
- [ ] Implement `readDir` for SAF
- [ ] Implement `exists` for SAF

### Phase 2: File Management
- [ ] Implement `mkdir` for SAF
- [ ] Implement `create` for SAF
- [ ] Implement `remove` for SAF
- [ ] Implement `rename` for SAF
- [ ] Implement `copyFile` for SAF

### Phase 3: Metadata & Watching
- [ ] Implement `stat` for SAF
- [ ] Implement `watch`/`unwatch` for SAF
- [ ] Test ContentObserver reliability

### Phase 4: Polish
- [ ] Error handling and user-friendly messages
- [ ] Documentation updates
- [ ] Example app demonstrating usage
- [ ] Performance optimization
