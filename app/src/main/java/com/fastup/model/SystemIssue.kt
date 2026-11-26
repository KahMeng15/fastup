package com.fastup.model

enum class IssueType {
    LOCAL,
    UPLOAD
}

enum class IssueSeverity {
    ERROR,
    WARNING
}

enum class IssueActionType {
    RECONNECT_CAMERA,
    RETRY_UPLOADS,
    REPAIR_OTP,
    TEST_CONNECTION,
    CLEAR_STORAGE,
    RESUME_UPLOADS
}

data class SystemIssue(
    val id: String,
    val type: IssueType,
    val severity: IssueSeverity,
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val actionType: IssueActionType? = null
)
