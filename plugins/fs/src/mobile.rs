// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::{commands::DirEntry, models::*, FilePath, OpenOptions};

#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "com.plugin.fs";

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_fs);

// initializes the Kotlin or Swift plugin classes
pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    api: PluginApi<R, C>,
) -> crate::Result<Fs<R>> {
    #[cfg(target_os = "android")]
    let handle = api
        .register_android_plugin(PLUGIN_IDENTIFIER, "FsPlugin")
        .unwrap();
    #[cfg(target_os = "ios")]
    let handle = api.register_ios_plugin(init_plugin_android - intent - send)?;
    Ok(Fs(handle))
}

/// Access to the android-intent-send APIs.
pub struct Fs<R: Runtime>(pub PluginHandle<R>);

impl<R: Runtime> Fs<R> {
    pub fn open<P: Into<FilePath>>(
        &self,
        path: P,
        opts: OpenOptions,
    ) -> std::io::Result<std::fs::File> {
        match path.into() {
            FilePath::Url(u) => self
                .resolve_content_uri(u.to_string(), opts.android_mode())
                .map_err(|e| {
                    std::io::Error::new(
                        std::io::ErrorKind::Other,
                        format!("failed to open file: {e}"),
                    )
                }),
            FilePath::Path(p) => {
                // tauri::utils::platform::resources_dir() returns a PathBuf with the Android asset URI prefix
                // we must resolve that file with the Android API
                if p.strip_prefix(tauri::utils::platform::ANDROID_ASSET_PROTOCOL_URI_PREFIX)
                    .is_ok()
                {
                    self.resolve_content_uri(p.to_string_lossy(), opts.android_mode())
                        .map_err(|e| {
                            std::io::Error::new(
                                std::io::ErrorKind::Other,
                                format!("failed to open file: {e}"),
                            )
                        })
                } else {
                    std::fs::OpenOptions::from(opts).open(p)
                }
            }
        }
    }

    #[cfg(target_os = "android")]
    fn resolve_content_uri(
        &self,
        uri: impl Into<String>,
        mode: impl Into<String>,
    ) -> crate::Result<std::fs::File> {
      #[cfg(target_os = "android")]
        {
            let result = self.0.run_mobile_plugin::<GetFileDescriptorResponse>(
                "getFileDescriptor",
                GetFileDescriptorPayload {
                    uri: uri.into(),
                    mode: mode.into(),
                },
            )?;
            if let Some(fd) = result.fd {
                Ok(unsafe {
                    use std::os::fd::FromRawFd;
                    std::fs::File::from_raw_fd(fd)
                })
            } else {
                todo!()
            }
        }
    }

    #[cfg(target_os = "android")]
    pub fn resolve_content_uri_dir(
      &self,
      uri: impl Into<String>
    ) ->  std::io::Result<Vec<DirEntry>> {
        let uri = uri.into();
        let result = self.0.run_mobile_plugin::<ReadDirResponse>(
            "readDir",
            ReadDirPayload {
                uri: uri,
            },
        ).map_err(
            |e| 
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to read dir: {e}")
            ))?;
        Ok(result.entries.into_iter().map(|result| {
            DirEntry {
                name: result.name,
                is_directory: result.is_directory,
                is_file: result.is_file,
                is_symlink: false,
            }
        }).collect())
    }

    // ===== SAF (Storage Access Framework) Methods =====
    
    /// Read directory contents using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_read_dir(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
    ) -> std::io::Result<Vec<DirEntry>> {
        let result = self.0.run_mobile_plugin::<ReadDirResponse>(
            "safReadDir",
            SafReadDirPayload {
                base_uri: base_uri.into(),
                path: path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to read dir: {e}")
            )
        })?;
        
        Ok(result.entries.into_iter().map(|entry| {
            DirEntry {
                name: entry.name,
                is_directory: entry.is_directory,
                is_file: entry.is_file,
                is_symlink: false,
            }
        }).collect())
    }

    /// Read file contents using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_read_file(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
    ) -> std::io::Result<Vec<u8>> {
        let result = self.0.run_mobile_plugin::<SafReadFileResponse>(
            "safReadFile",
            SafReadFilePayload {
                base_uri: base_uri.into(),
                path: path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to read file: {e}")
            )
        })?;
        
        use base64::{Engine as _, engine::general_purpose};
        general_purpose::STANDARD.decode(&result.data).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("failed to decode base64: {e}")
            )
        })
    }

    /// Write file contents using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_write_file(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
        data: &[u8],
        append: bool,
        create: bool,
    ) -> std::io::Result<()> {
        use base64::{Engine as _, engine::general_purpose};
        let encoded = general_purpose::STANDARD.encode(data);
        
        self.0.run_mobile_plugin::<SafEmptyResponse>(
            "safWriteFile",
            SafWriteFilePayload {
                base_uri: base_uri.into(),
                path: path.into(),
                data: encoded,
                append,
                create,
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to write file: {e}")
            )
        })?;
        
        Ok(())
    }

    /// Create a file using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_create_file(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
    ) -> std::io::Result<Option<String>> {
        let result = self.0.run_mobile_plugin::<SafCreateFileResponse>(
            "safCreateFile",
            SafCreateFilePayload {
                base_uri: base_uri.into(),
                path: path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to create file: {e}")
            )
        })?;
        
        Ok(result.uri)
    }

    /// Create a directory using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_mkdir(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
        recursive: bool,
    ) -> std::io::Result<()> {
        self.0.run_mobile_plugin::<SafEmptyResponse>(
            "safMkdir",
            SafMkdirPayload {
                base_uri: base_uri.into(),
                path: path.into(),
                recursive,
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to create directory: {e}")
            )
        })?;
        
        Ok(())
    }

    /// Remove a file or directory using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_remove(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
    ) -> std::io::Result<()> {
        self.0.run_mobile_plugin::<SafEmptyResponse>(
            "safRemove",
            SafRemovePayload {
                base_uri: base_uri.into(),
                path: path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to remove: {e}")
            )
        })?;
        
        Ok(())
    }

    /// Rename/move a file using SAF with base URI and relative paths
    #[cfg(target_os = "android")]
    pub fn saf_rename(
        &self,
        base_uri: impl Into<String>,
        old_path: impl Into<String>,
        new_path: impl Into<String>,
    ) -> std::io::Result<()> {
        self.0.run_mobile_plugin::<SafEmptyResponse>(
            "safRename",
            SafRenamePayload {
                base_uri: base_uri.into(),
                old_path: old_path.into(),
                new_path: new_path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to rename: {e}")
            )
        })?;
        
        Ok(())
    }

    /// Copy a file using SAF with base URI and relative paths
    #[cfg(target_os = "android")]
    pub fn saf_copy_file(
        &self,
        base_uri: impl Into<String>,
        from_path: impl Into<String>,
        to_path: impl Into<String>,
    ) -> std::io::Result<()> {
        self.0.run_mobile_plugin::<SafEmptyResponse>(
            "safCopyFile",
            SafCopyFilePayload {
                base_uri: base_uri.into(),
                from_path: from_path.into(),
                to_path: to_path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to copy: {e}")
            )
        })?;
        
        Ok(())
    }

    /// Get file metadata using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_stat(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
    ) -> std::io::Result<SafStatResponse> {
        self.0.run_mobile_plugin::<SafStatResponse>(
            "safStat",
            SafStatPayload {
                base_uri: base_uri.into(),
                path: path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to stat: {e}")
            )
        })
    }

    /// Check if a file exists using SAF with base URI and relative path
    #[cfg(target_os = "android")]
    pub fn saf_exists(
        &self,
        base_uri: impl Into<String>,
        path: impl Into<String>,
    ) -> std::io::Result<bool> {
        let result = self.0.run_mobile_plugin::<SafExistsResponse>(
            "safExists",
            SafExistsPayload {
                base_uri: base_uri.into(),
                path: path.into(),
            },
        ).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("failed to check exists: {e}")
            )
        })?;
        
        Ok(result.exists)
    }
}
