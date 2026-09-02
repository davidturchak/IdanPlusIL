package com.idanplusil.tv.data.update

enum class UpdateError {
    // Check and download
    NoNetwork, Timeout, BadResponse, Malformed,
    // Download
    SizeMismatch, ChecksumMismatch, Storage,
    // Install
    WrongPackage, NoInstaller, SettingsUnavailable,
}
