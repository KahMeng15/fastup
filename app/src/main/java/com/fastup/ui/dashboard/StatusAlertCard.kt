package com.fastup.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastup.model.IssueSeverity
import com.fastup.model.IssueType
import com.fastup.model.SystemIssue
import com.fastup.ui.theme.*

@Composable
fun StatusAlertCard(
    issues: List<SystemIssue>,
    onAction: (SystemIssue) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = issues.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val hasErrors = issues.any { it.severity == IssueSeverity.ERROR }
        val cardBorderColor = if (hasErrors) Rose500.copy(alpha = 0.5f) else Amber500.copy(alpha = 0.5f)
        val headerColor = if (hasErrors) Rose400 else Amber400

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            border = BorderStroke(1.dp, cardBorderColor),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (hasErrors) Icons.Default.ErrorOutline else Icons.Default.Warning,
                            contentDescription = null,
                            tint = headerColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Alerts & Active Issues (${issues.size})",
                            color = Slate100,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        color = headerColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (hasErrors) "ACTION REQUIRED" else "ATTENTION",
                            color = headerColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                HorizontalDivider(color = Slate700, thickness = 0.75.dp)

                // Grouped Issues (Local vs Upload)
                val localIssues = issues.filter { it.type == IssueType.LOCAL }
                val uploadIssues = issues.filter { it.type == IssueType.UPLOAD }

                if (localIssues.isNotEmpty()) {
                    IssueGroupSection(
                        groupTitle = "Local Camera & Transfer",
                        groupIcon = Icons.Default.CameraAlt,
                        groupColor = Amber400,
                        issues = localIssues,
                        onAction = onAction
                    )
                }

                if (uploadIssues.isNotEmpty()) {
                    IssueGroupSection(
                        groupTitle = "Upload Pipeline & Server",
                        groupIcon = Icons.Default.CloudUpload,
                        groupColor = if (uploadIssues.any { it.severity == IssueSeverity.ERROR }) Rose400 else Blue400,
                        issues = uploadIssues,
                        onAction = onAction
                    )
                }
            }
        }
    }
}

@Composable
private fun IssueGroupSection(
    groupTitle: String,
    groupIcon: androidx.compose.ui.graphics.vector.ImageVector,
    groupColor: Color,
    issues: List<SystemIssue>,
    onAction: (SystemIssue) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Icon(
                imageVector = groupIcon,
                contentDescription = null,
                tint = groupColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = groupTitle.uppercase(),
                color = Slate400,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        issues.forEach { issue ->
            IssueItemRow(issue = issue, onAction = onAction)
        }
    }
}

@Composable
private fun IssueItemRow(
    issue: SystemIssue,
    onAction: (SystemIssue) -> Unit
) {
    val isError = issue.severity == IssueSeverity.ERROR
    val accentColor = if (isError) Rose400 else Amber400
    val containerBg = if (isError) Rose500.copy(alpha = 0.10f) else Amber500.copy(alpha = 0.08f)

    Surface(
        color = containerBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = issue.title,
                        color = Slate100,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = issue.message,
                    color = Slate300,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (!issue.actionLabel.isNullOrBlank() && issue.actionType != null) {
                Button(
                    onClick = { onAction(issue) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isError) Rose600 else Blue600,
                        contentColor = Slate100
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = issue.actionLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
