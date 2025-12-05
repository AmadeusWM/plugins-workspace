// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GetFileDescriptorPayload {
    pub uri: String,
    pub mode: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GetFileDescriptorResponse {
    pub fd: Option<i32>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadDirPayload {
    pub uri: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadDirEntry {
    pub name: String,
    pub is_directory: bool,
    pub is_file: bool,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadDirResponse {
    pub entries: Vec<ReadDirEntry>
}

// ===== SAF (Storage Access Framework) Models =====

/// Payload for SAF read directory operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafReadDirPayload {
    pub base_uri: String,
    pub path: String,
}

/// Payload for SAF read file operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafReadFilePayload {
    pub base_uri: String,
    pub path: String,
}

/// Response for SAF read file operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafReadFileResponse {
    pub data: String, // Base64 encoded
}

/// Payload for SAF write file operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafWriteFilePayload {
    pub base_uri: String,
    pub path: String,
    pub data: String, // Base64 encoded
    #[serde(default)]
    pub append: bool,
    #[serde(default = "default_true")]
    pub create: bool,
}

fn default_true() -> bool {
    true
}

/// Payload for SAF create file operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafCreateFilePayload {
    pub base_uri: String,
    pub path: String,
}

/// Response for SAF create file operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafCreateFileResponse {
    pub uri: Option<String>,
}

/// Payload for SAF mkdir operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafMkdirPayload {
    pub base_uri: String,
    pub path: String,
    #[serde(default)]
    pub recursive: bool,
}

/// Payload for SAF remove operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafRemovePayload {
    pub base_uri: String,
    pub path: String,
}

/// Payload for SAF rename operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafRenamePayload {
    pub base_uri: String,
    pub old_path: String,
    pub new_path: String,
}

/// Payload for SAF copy file operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafCopyFilePayload {
    pub base_uri: String,
    pub from_path: String,
    pub to_path: String,
}

/// Payload for SAF stat operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafStatPayload {
    pub base_uri: String,
    pub path: String,
}

/// Response for SAF stat operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafStatResponse {
    pub is_file: bool,
    pub is_directory: bool,
    pub size: u64,
    pub mtime: Option<u64>,
}

/// Payload for SAF exists operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafExistsPayload {
    pub base_uri: String,
    pub path: String,
}

/// Response for SAF exists operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafExistsResponse {
    pub exists: bool,
}

/// Empty response for operations that don't return data
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafEmptyResponse {}

/// Payload for SAF watch operation (only Serialize, no Deserialize since Channel doesn't impl Deserialize)
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafWatchPayload<T: serde::Serialize> {
    pub base_uri: String,
    pub path: String,
    #[serde(default)]
    pub recursive: bool,
    pub on_event: tauri::ipc::Channel<T>,
}

/// Event from SAF content observer
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafWatchEvent {
    pub watcher_id: i32,
    pub uri: Option<String>,
    pub self_change: bool,
    #[serde(rename = "type")]
    pub event_type: String,
}

/// Response from SAF watch operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafWatchResponse {
    pub watcher_id: i32,
}

/// Payload for SAF unwatch operation
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafUnwatchPayload {
    pub watcher_id: i32,
}
