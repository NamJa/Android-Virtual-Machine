package dev.jongwoo.androidvm.bridge

data class PermissionReason(
    val bridge: BridgeType,
    val operation: String,
    val permission: String,
    val userMessage: String,
)
