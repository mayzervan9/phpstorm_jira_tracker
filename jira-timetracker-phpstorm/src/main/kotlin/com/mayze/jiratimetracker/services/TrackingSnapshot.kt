package com.mayze.jiratimetracker.services

import com.intellij.util.messages.Topic

data class TrackingSnapshot(
    val running: Boolean,
    val activeIssueKey: String?,
    val currentIssueTrackedSeconds: Int,
    val todayByIssueSeconds: Map<String, Int>,
) {
    val totalTodaySeconds: Int = todayByIssueSeconds.values.sum()
}

interface TrackingListener {
    fun onTrackingUpdated(snapshot: TrackingSnapshot)
}

object TrackingTopics {
    @JvmField
    val TRACKING_UPDATES: Topic<TrackingListener> =
        Topic.create("jira.time.tracking.updates", TrackingListener::class.java)

    @JvmField
    val TRACKING_STARTED: Topic<TrackingStartedListener> =
        Topic.create("jira.time.tracking.started", TrackingStartedListener::class.java)

    @JvmField
    val TRACKING_STOPPED: Topic<TrackingStoppedListener> =
        Topic.create("jira.time.tracking.stopped", TrackingStoppedListener::class.java)
}

interface TrackingStartedListener {
    fun onTrackingStarted(issueKey: String)
}

interface TrackingStoppedListener {
    fun onTrackingStopped(issueKey: String)
}

