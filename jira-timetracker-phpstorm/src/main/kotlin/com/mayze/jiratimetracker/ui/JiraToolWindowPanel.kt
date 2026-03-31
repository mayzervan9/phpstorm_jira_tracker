package com.mayze.jiratimetracker.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.jira.*
import com.mayze.jiratimetracker.services.*
import com.mayze.jiratimetracker.settings.JiraProfilesState
import com.mayze.jiratimetracker.settings.ManageProfilesDialog
import com.mayze.jiratimetracker.util.formatSeconds
import com.mayze.jiratimetracker.util.formatSecondsShort
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@Suppress("TooManyFunctions")
class JiraToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    private companion object {
        const val MAX_COMMENTS = 80; const val MAX_WORKLOGS = 120
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
    }
    private val profilesBtn = ib(AllIcons.General.Settings, "Manage Jira Profiles")
    private val connectBtn  = ib(AllIcons.Actions.Refresh,  "Connect")
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply { isVisible = false }
    private val profileLabel = JBLabel("No profile").apply { font = font.deriveFont(Font.BOLD, 11f); foreground = gray(); border = JBUI.Borders.empty(0,6) }
    private val statusLabel  = JBLabel("").apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = gray(); border = JBUI.Borders.empty(0,4) }

    private val statusFilterCombo = ComboBox(DefaultComboBoxModel(arrayOf("All statuses")))
    private val noEstimateCheck = JBCheckBox("No estimate only").apply { isOpaque = false }
    private val activeSprintCheck = JBCheckBox("Active sprint", true).apply { isOpaque = false; toolTipText = "Show only issues from the active sprint" }
    private val myProjectsCheck = JBCheckBox("My projects", true).apply { isOpaque = false; toolTipText = "Show only projects where you have issues" }
    private var allProjectsCache: List<JiraProject> = emptyList()
    private var myProjectKeysCache: Set<String> = emptySet()
    private var myProjectsCacheTime: Long = 0
    private val scopeCombo = ComboBox(DefaultComboBoxModel(arrayOf("My issues", "Involved", "All project"))).apply { toolTipText = "Filter scope" }
    private val sortCombo = ComboBox(DefaultComboBoxModel(arrayOf("Date ↓", "Date ↑", "Priority ↓", "Priority ↑", "Key ↓", "Key ↑"))).apply { toolTipText = "Sort issues" }
    private val projectCombo = ComboBox<JiraProject>()
    private val searchField  = SearchTextField(false).apply { textEditor.emptyText.text = "Filter issues..." }
    private val issuesModel  = DefaultListModel<JiraIssue>()
    private val issuesList   = JBList(issuesModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION; cellRenderer = JiraIssueRenderer(); emptyText.text = "Connect to load issues" }
    private var allIssues: List<JiraIssue> = emptyList()
    private var availableStatuses: List<String> = emptyList()
    private var statusIdMap: Map<String, String> = emptyMap()
    private val issueTitleLabel = JBLabel("").apply { font = font.deriveFont(Font.BOLD, 13f); border = JBUI.Borders.empty(6,8,2,8) }
    private val issueStatusLabel = JBLabel("").apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = gray() }
    private val estimateLabel    = JBLabel("").apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = gray() }
    private val openInBrowserBtn = ib(AllIcons.General.Web, "Open in browser")
    private val copyLinkBtn      = ib(AllIcons.Actions.Copy, "Copy issue URL")
    private val copyKeyBtn       = ib(AllIcons.Nodes.CopyOfFolder, "Copy issue key")
    private val setEstimateBtn   = ib(AllIcons.Actions.Edit, "Set estimate")
    private val changeStatusBtn  = JButton("Status").apply { font = font.deriveFont(Font.PLAIN, 11f); icon = AllIcons.Actions.Forward }
    private val descPane = javax.swing.JEditorPane("text/html", "").apply {
        isEditable = false; border = JBUI.Borders.empty(8); isOpaque = false
        background = JBColor.PanelBackground
        putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { e -> if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) { try { Desktop.getDesktop().browse(e.url.toURI()) } catch (_: Throwable) {} } }
    }
    private val fieldsPane = javax.swing.JEditorPane("text/html", "").apply {
        isEditable = false; border = JBUI.Borders.empty(4)
        addHyperlinkListener { e -> if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) { try { Desktop.getDesktop().browse(e.url.toURI()) } catch (_: Throwable) {} } }
    }
    private val commentsModel = DefaultListModel<JiraComment>()
    private val commentsList  = JBList(commentsModel).apply { cellRenderer = CommentListCellRenderer(); selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val worklogsModel = DefaultListModel<JiraWorklog>()
    private val worklogsList  = JBList(worklogsModel).apply { cellRenderer = WorklogListCellRenderer(); selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val commentInput  = JBTextArea(3,0).apply { lineWrap=true; wrapStyleWord=true; border=JBUI.Borders.empty(6) }
    private val addCommentBtn = JButton("Add Comment").apply { icon = AllIcons.General.Add }
    private val mrInput       = JBTextArea(2,0).apply { lineWrap=true; wrapStyleWord=true; border=JBUI.Borders.empty(6) }
    private val saveMrBtn     = JButton("Save as Comment").apply { icon = AllIcons.Vcs.Merge }
    private val addWorklogBtn = JButton("Add Worklog").apply { icon = AllIcons.Vcs.History }
    private val startStopBtn  = JButton("Start").apply { font = font.deriveFont(Font.BOLD, 12f); preferredSize = Dimension(120,32) }
    private val timerLabel    = JBLabel("0h 0m").apply { font = font.deriveFont(Font.BOLD, 22f); horizontalAlignment = JLabel.CENTER }
    private val todayIssueLabel = JBLabel("Issue: 0h 0m").apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = gray() }
    private val todayTotalLabel = JBLabel("Total: 0h 0m").apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = gray() }
    private val todayStatsArea  = roArea().apply { rows = 5 }
    private val historyModel    = DefaultListModel<HistoryEntry>()
    private val historyList     = JBList(historyModel).apply { cellRenderer = HistoryListCellRenderer(); selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val activityModel   = DefaultListModel<com.mayze.jiratimetracker.jira.JiraActivity>()
    private val activityList    = JBList(activityModel).apply { cellRenderer = ActivityListCellRenderer(); selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val historyProgressLabel = JBLabel("").apply { isVisible = false; font = font.deriveFont(Font.PLAIN, 11f); border = JBUI.Borders.emptyLeft(8) }
    private val activityProgressLabel = JBLabel("").apply { isVisible = false; font = font.deriveFont(Font.PLAIN, 11f); border = JBUI.Borders.emptyLeft(8) }
    private val toolbarTodayLabel = JBLabel("Today: 0h 0m").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor(Color(0x1B7F3A), Color(0x4CAF50))
        border = JBUI.Borders.empty(0, 8)
    }
    private var currentIssue: JiraIssue? = null
    private val commentInFlight = AtomicBoolean(false)
    private val mrInFlight      = AtomicBoolean(false)
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)

    init {
        minimumSize = Dimension(480, 320)
        cardPanel.add(buildWelcome(), "welcome")
        cardPanel.add(buildMain(), "main")
        add(cardPanel, BorderLayout.CENTER)
        wire(); subscribeTracking()
        // Always show welcome first, then auto-connect if profile exists
        cards.show(cardPanel, "welcome")
        if (service<JiraProfilesState>().activeProfile() != null) {
            showMain()
        }
    }
    private fun showMain() { refreshProfileLabel(); refreshButtons(project.service<TimeTrackerService>().getSnapshot()); cards.show(cardPanel, "main"); if (projectCombo.itemCount == 0) connectAndLoad() }
    private fun buildWelcome(): JPanel {
        val btn = JButton("Add Jira Profile").apply { font = font.deriveFont(Font.BOLD, 13f); icon = AllIcons.General.Add; preferredSize = Dimension(200,36) }
        btn.addActionListener { ManageProfilesDialog(project).showAndGet(); if (service<JiraProfilesState>().activeProfile() != null) { showMain(); connectAndLoad() } }
        val connectBtn2 = JButton("Connect").apply { font = font.deriveFont(Font.BOLD, 13f); icon = AllIcons.Actions.Refresh; preferredSize = Dimension(200,36); isVisible = service<JiraProfilesState>().activeProfile() != null }
        connectBtn2.addActionListener { showMain(); connectAndLoad() }
        return JPanel(java.awt.GridBagLayout()).apply {
            background = JBColor.PanelBackground
            val gbc = java.awt.GridBagConstraints().apply { gridx = 0; gridy = 0; anchor = java.awt.GridBagConstraints.CENTER }
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                val logo = JBLabel(AllIcons.Plugins.PluginLogo).apply { alignmentX = CENTER_ALIGNMENT }
                val title = JBLabel("Jira Time Tracker").apply { font = font.deriveFont(Font.BOLD, 18f); alignmentX = CENTER_ALIGNMENT; border = JBUI.Borders.emptyTop(16) }
                val profileName = service<JiraProfilesState>().activeProfile()?.name
                val subtitleText = if (profileName != null)
                    "<html><div style='text-align:center'>Profile: <b>$profileName</b><br/>Click Connect to load projects.</div></html>"
                else
                    "<html><div style='text-align:center'>Track time, manage worklogs<br/>and statuses from your IDE.</div></html>"
                val subtitle = JBLabel(subtitleText).apply { foreground = gray(); alignmentX = CENTER_ALIGNMENT; border = JBUI.Borders.empty(10,0,24,0) }
                btn.alignmentX = CENTER_ALIGNMENT
                connectBtn2.alignmentX = CENTER_ALIGNMENT
                add(logo); add(title); add(subtitle)
                if (profileName != null) { add(connectBtn2); add(javax.swing.Box.createVerticalStrut(8)) }
                add(btn)
            }
            add(inner, gbc)
        }
    }
    private fun buildMain() = JPanel(BorderLayout()).apply { add(buildToolbar(), BorderLayout.NORTH); add(buildSplitter(), BorderLayout.CENTER) }
    private fun buildToolbar() = JPanel(BorderLayout()).apply {
        border = BorderFactory.createMatteBorder(0,0,1,0, JBColor.border())
        add(JPanel(FlowLayout(FlowLayout.LEFT,4,4)).apply { isOpaque=false; add(profilesBtn); add(connectBtn); add(loadingIcon); add(profileLabel) }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT,8,4)).apply { isOpaque=false; add(toolbarTodayLabel); add(statusLabel) }, BorderLayout.EAST)
    }
    private fun buildSplitter() = JBSplitter(false, 0.28f).apply { firstComponent = buildLeft(); secondComponent = buildRight() }
    private fun buildLeft(): JPanel {
        val top = JPanel(BorderLayout(4,4)).apply { border = JBUI.Borders.empty(6,8,4,8); add(JBLabel("Project:").apply { preferredSize = Dimension(52,24) }, BorderLayout.WEST); add(projectCombo, BorderLayout.CENTER); add(myProjectsCheck, BorderLayout.EAST) }
        val filters = JPanel(FlowLayout(FlowLayout.LEFT,4,2)).apply { isOpaque=false; border=JBUI.Borders.empty(0,8,2,8); add(scopeCombo); add(statusFilterCombo); add(sortCombo); add(noEstimateCheck); add(activeSprintCheck) }
        return JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(top, BorderLayout.NORTH); add(JPanel(BorderLayout()).apply { add(JPanel(BorderLayout()).apply { border=JBUI.Borders.empty(0,8,2,8); add(searchField, BorderLayout.CENTER) }, BorderLayout.NORTH); add(filters, BorderLayout.CENTER) }, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(JBScrollPane(issuesList).apply { border=JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
    }

    private fun buildRight(): JPanel {
        val metaBar = JPanel(FlowLayout(FlowLayout.LEFT,6,2)).apply { isOpaque=false; border=JBUI.Borders.empty(0,8,4,8); add(issueStatusLabel); add(estimateLabel); add(copyKeyBtn); add(openInBrowserBtn); add(copyLinkBtn); add(setEstimateBtn); add(changeStatusBtn) }
        val detailTabs = JTabbedPane().apply {
            addTab("Description", AllIcons.Actions.Preview, sp(descPane))
            addTab("Comments", AllIcons.General.Balloon, sp(commentsList))
            addTab("Worklogs", AllIcons.Vcs.History, sp(worklogsList))
            addTab("Fields", AllIcons.Nodes.DataTables, sp(fieldsPane))
            addChangeListener { val key = currentIssue?.key ?: return@addChangeListener; when (selectedIndex) { 1 -> refreshComments(key); 2 -> refreshWorklogs(key); 3 -> refreshFields(key) } }
        }
        val actionTabs = JTabbedPane().apply { addTab("Tracker", AllIcons.Actions.Execute, buildTracker()); addTab("Comment", AllIcons.General.Add, buildCommentP()); addTab("MR/PR", AllIcons.Vcs.Merge, buildMrP()); addTab("Today", AllIcons.Vcs.History, buildTodayP()) }
        val issuePanel = JPanel(BorderLayout()).apply {
            add(JBSplitter(true, 0.60f).apply {
                firstComponent = JPanel(BorderLayout()).apply { add(JPanel(BorderLayout()).apply { add(issueTitleLabel, BorderLayout.NORTH); add(metaBar, BorderLayout.CENTER) }, BorderLayout.NORTH); add(detailTabs, BorderLayout.CENTER) }
                secondComponent = actionTabs
            }, BorderLayout.CENTER)
        }
        val topTabs = JTabbedPane().apply {
            addTab("Issue", AllIcons.Nodes.Tag, issuePanel)
            addTab("Activity", AllIcons.General.Balloon, buildActivityP())
            addTab("History", AllIcons.Actions.ListFiles, buildHistoryP())
            addTab("Contact Dev", AllIcons.General.User, JPanel())
            addChangeListener { if (selectedIndex == 3) { selectedIndex = 0; try { Desktop.getDesktop().browse(java.net.URI("https://t.me/mayzervan9")) } catch (_: Throwable) {} } }
        }
        return JPanel(BorderLayout()).apply { add(topTabs, BorderLayout.CENTER) }
    }
    private fun buildTracker() = JPanel(BorderLayout()).apply {
        border=JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0,4)).apply { border=JBUI.Borders.empty(12,16,8,16); isOpaque=false; add(timerLabel, BorderLayout.CENTER); add(JPanel(FlowLayout(FlowLayout.CENTER,16,0)).apply { isOpaque=false; add(todayIssueLabel); add(todayTotalLabel) }, BorderLayout.SOUTH) }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.CENTER,8,0)).apply { isOpaque=false; add(startStopBtn); add(addWorklogBtn) }, BorderLayout.SOUTH)
    }
    private fun buildCommentP() = JPanel(BorderLayout(0,6)).apply { border=JBUI.Borders.empty(8); add(JBScrollPane(commentInput).apply { border=BorderFactory.createLineBorder(JBColor.border(),1,true) }, BorderLayout.CENTER); add(JPanel(FlowLayout(FlowLayout.RIGHT,0,0)).apply { isOpaque=false; add(addCommentBtn) }, BorderLayout.SOUTH) }
    private fun buildMrP() = JPanel(BorderLayout(0,6)).apply { border=JBUI.Borders.empty(8); add(JBLabel("MR/PR link:").apply { border=JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH); add(JBScrollPane(mrInput).apply { border=BorderFactory.createLineBorder(JBColor.border(),1,true) }, BorderLayout.CENTER); add(JPanel(FlowLayout(FlowLayout.RIGHT,0,0)).apply { isOpaque=false; add(saveMrBtn) }, BorderLayout.SOUTH) }
    private fun buildTodayP() = JPanel(BorderLayout()).apply { border=JBUI.Borders.empty(8); add(JBLabel("Time logged today:").apply { border=JBUI.Borders.emptyBottom(6); font=font.deriveFont(Font.BOLD) }, BorderLayout.NORTH); add(JBScrollPane(todayStatsArea).apply { border=JBUI.Borders.empty() }, BorderLayout.CENTER) }
    private fun buildHistoryP(): JPanel {
        val loadBtn = JButton("Load History").apply { icon = AllIcons.Vcs.History }
        loadBtn.addActionListener { loadWorklogHistory() }
        return JPanel(BorderLayout(0,6)).apply {
            border = JBUI.Borders.empty(8)
            add(JPanel(FlowLayout(FlowLayout.LEFT,8,0)).apply { isOpaque=false; add(JBLabel("Daily totals from Jira worklogs:").apply { font=font.deriveFont(Font.BOLD) }); add(loadBtn); add(historyProgressLabel) }, BorderLayout.NORTH)
            add(sp(historyList), BorderLayout.CENTER)
        }
    }
    private fun buildActivityP(): JPanel {
        val loadBtn = JButton("Refresh").apply { icon = AllIcons.Actions.Refresh }
        loadBtn.addActionListener { loadActivity() }
        activityList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val activity = activityList.selectedValue ?: return
                    // Switch to Issue tab and load the issue
                    val issue = allIssues.find { it.key == activity.issueKey }
                    if (issue != null) {
                        val idx = issuesModel.let { m -> (0 until m.size()).firstOrNull { m[it].key == issue.key } }
                        if (idx != null) issuesList.selectedIndex = idx
                    }
                    loadIssueDetails(activity.issueKey)
                }
            }
        })
        return JPanel(BorderLayout(0,6)).apply {
            border = JBUI.Borders.empty(8)
            add(JPanel(FlowLayout(FlowLayout.LEFT,8,0)).apply {
                isOpaque=false
                add(JBLabel("Recent activity (last 24h):").apply { font=font.deriveFont(Font.BOLD) })
                add(loadBtn)
                add(activityProgressLabel)
            }, BorderLayout.NORTH)
            add(sp(activityList), BorderLayout.CENTER)
            add(JBLabel("<html><small>Double-click to open issue</small></html>").apply { foreground = gray(); border = JBUI.Borders.emptyTop(4) }, BorderLayout.SOUTH)
        }
    }
    // ── Wiring ────────────────────────────────────────────────────────────────
    private fun wire() {
        profilesBtn.addActionListener { openProfiles() }
        connectBtn.addActionListener  { connectAndLoad() }
        projectCombo.addActionListener { onProjectSelected() }
        statusFilterCombo.addActionListener { loadIssues() }
        noEstimateCheck.addActionListener   { loadIssues() }
        activeSprintCheck.addActionListener  { loadIssues() }
        myProjectsCheck.addActionListener   { refreshProjectList() }
        scopeCombo.addActionListener        { loadIssues() }
        sortCombo.addActionListener         { filterIssues() }
        searchField.addDocumentListener(object : DocumentListener { override fun insertUpdate(e: DocumentEvent?)=filterIssues(); override fun removeUpdate(e: DocumentEvent?)=filterIssues(); override fun changedUpdate(e: DocumentEvent?)=filterIssues() })
        issuesList.addListSelectionListener { if (!it.valueIsAdjusting) { val i = issuesList.selectedValue ?: return@addListSelectionListener; project.service<TimeTrackerService>().setActiveIssue(i.key); loadIssueDetails(i.key) } }
        openInBrowserBtn.addActionListener { openBrowser() }
        copyLinkBtn.addActionListener      { copyLink() }
        copyKeyBtn.addActionListener       { currentIssue?.let { cp(it.key); setStatus(false, "Copied: ${it.key}") } }
        setEstimateBtn.addActionListener   { openSetEstimate() }
        changeStatusBtn.addActionListener  { openChangeStatus() }
        startStopBtn.addActionListener { val t = project.service<TimeTrackerService>(); if (t.isRunning()) t.stop() else { val i = issuesList.selectedValue ?: return@addActionListener; t.start(i.key) } }
        addWorklogBtn.addActionListener { openAddWorklog() }
        addCommentBtn.addActionListener { submitComment() }
        saveMrBtn.addActionListener     { submitMr() }
    }
    private fun subscribeTracking() {
        val bus = project.messageBus.connect()
        bus.subscribe(TrackingTopics.TRACKING_UPDATES, object : TrackingListener { override fun onTrackingUpdated(snapshot: TrackingSnapshot) = runUi { renderTracking(snapshot) } })
        bus.subscribe(TrackingTopics.TRACKING_STARTED, object : TrackingStartedListener { override fun onTrackingStarted(issueKey: String) = onStarted(issueKey) })
        bus.subscribe(TrackingTopics.TRACKING_STOPPED, object : TrackingStoppedListener { override fun onTrackingStopped(issueKey: String) = onStopped(issueKey) })
        renderTracking(project.service<TimeTrackerService>().getSnapshot())
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private fun openProfiles() { val d = ManageProfilesDialog(project); d.showAndGet(); if (service<JiraProfilesState>().activeProfile() != null) { showMain(); connectAndLoad() } }
    private fun onStarted(issueKey: String) { runBg(onError={}) { val ts = try { service<JiraService>().apiFromSettings().getTransitions(issueKey) } catch (_: Throwable) { return@runBg }; if (ts.isEmpty()) return@runBg; val cs = currentIssue?.status; runUi { val d = TransitionPickerDialog(project, issueKey, ts, cs); d.title = "Tracking started — move \"$issueKey\"?"; if (d.showAndGet()) d.selectedTransition?.let { applyTransition(issueKey, it) } } } }
    private fun onStopped(issueKey: String) { runBg(onError={}) { val ts = try { service<JiraService>().apiFromSettings().getTransitions(issueKey) } catch (_: Throwable) { return@runBg }; if (ts.isEmpty()) return@runBg; val cs = currentIssue?.status; runUi { val d = TransitionPickerDialog(project, issueKey, ts, cs); d.title = "Tracking stopped — move \"$issueKey\"?"; if (d.showAndGet()) d.selectedTransition?.let { applyTransition(issueKey, it) } } } }
    private fun applyTransition(issueKey: String, t: JiraTransition) {
        // Skip if transitioning to the same status
        if (t.name.equals(currentIssue?.status, ignoreCase = true)) { setStatus(false, "Already in \"${t.name}\""); return }
        runBg(onError = { setStatus(false, "Transition error: ${it.message}") }) { service<JiraService>().apiFromSettings().doTransition(issueKey, t.id); runUi { setStatus(false, "$issueKey → ${t.name}"); if (currentIssue?.key == issueKey) loadIssueDetails(issueKey); loadIssues() } }
    }
    private fun connectAndLoad() {
        if (service<JiraProfilesState>().activeProfile() == null) { setStatus(false, "No active profile"); return }
        setStatus(true, "Connecting...")
        runBg(onError = { setStatus(false, "Error: ${it.message}") }) {
            val api = service<JiraService>().apiFromSettings()
            val name = api.testConnection()
            val projects = api.getProjects().sortedBy { it.name.lowercase() }
            allProjectsCache = projects

            // Find which projects have my issues (cache for 15 min)
            val now = System.currentTimeMillis()
            if (myProjectKeysCache.isEmpty() || now - myProjectsCacheTime > 15 * 60 * 1000) {
                try {
                    val jql = "(assignee = currentUser() OR reporter = currentUser() OR watcher = currentUser()) ORDER BY updated DESC"
                    val body = org.json.JSONObject().put("jql", jql).put("maxResults", 200)
                        .put("fields", org.json.JSONArray().put("project"))
                    val (code, json) = api.searchPost(body)
                    if (code == 200) {
                        val issues = json.optJSONArray("issues") ?: org.json.JSONArray()
                        val keys = mutableSetOf<String>()
                        for (i in 0 until issues.length()) {
                            val f = issues.optJSONObject(i)?.optJSONObject("fields")
                            val pk = f?.optJSONObject("project")?.optString("key")
                            if (!pk.isNullOrBlank()) keys.add(pk)
                        }
                        myProjectKeysCache = keys
                        myProjectsCacheTime = now
                    }
                } catch (_: Throwable) {}
            }

            runUi {
                setStatus(false, "Connected as $name")
                refreshProjectList()
            }
        }
    }

    private fun refreshProjectList() {
        val prev = projectCombo.selectedItem as? JiraProject
        val filtered = if (myProjectsCheck.isSelected && myProjectKeysCache.isNotEmpty())
            allProjectsCache.filter { it.key in myProjectKeysCache }
        else allProjectsCache
        projectCombo.removeAllItems()
        filtered.forEach { projectCombo.addItem(it) }
        // Restore selection if possible
        val restore = filtered.find { it.key == prev?.key }
        if (restore != null) projectCombo.selectedItem = restore
        else if (filtered.isNotEmpty()) projectCombo.selectedIndex = 0
    }
    private fun onProjectSelected() {
        val p = projectCombo.selectedItem as? JiraProject ?: return
        runBg(onError={}) {
            val pairs = try { service<JiraService>().apiFromSettings().getProjectStatuses(p.key) } catch (_: Throwable) { emptyList() }
            runUi {
                statusIdMap = pairs.toMap()
                availableStatuses = pairs.map { it.first }
                val m = statusFilterCombo.model as DefaultComboBoxModel
                m.removeAllElements()
                m.addElement("All statuses")
                pairs.forEach { m.addElement(it.first) }
                statusFilterCombo.selectedIndex = 0
            }
        }
        loadIssues()
    }
    private fun loadIssues() {
        val p = projectCombo.selectedItem as? JiraProject ?: return
        val sel = statusFilterCombo.selectedItem as? String
        val statusIds = if (sel == null || sel == "All statuses") emptyList() else listOfNotNull(statusIdMap[sel])
        setStatus(true, "Loading...")
        runBg(onError = { setStatus(false, "Error: ${it.message}") }) {
            val scope = scopeCombo.selectedItem as? String ?: "My issues"
            val issues = service<JiraService>().apiFromSettings().searchMyIssues(p.key, 100, statusIds, noEstimateCheck.isSelected, scope, activeSprintCheck.isSelected)
            runUi { allIssues = issues; setStatus(false, "${p.key} - ${issues.size} issues"); filterIssues(); if (issuesModel.size() > 0) issuesList.selectedIndex = 0 }
        }
    }
    private fun filterIssues() {
        val q = searchField.text.trim().lowercase()
        var f = if (q.isBlank()) allIssues else allIssues.filter { it.key.lowercase().contains(q) || it.summary.lowercase().contains(q) }
        val sort = sortCombo.selectedItem as? String ?: "Date ↓"
        f = when (sort) {
            "Date ↑" -> f.sortedBy { it.created }
            "Date ↓" -> f.sortedByDescending { it.created }
            "Priority ↓" -> f.sortedBy { priorityOrder(it.priority) }
            "Priority ↑" -> f.sortedByDescending { priorityOrder(it.priority) }
            "Key ↑" -> f.sortedBy { it.key }
            "Key ↓" -> f.sortedByDescending { it.key }
            else -> f
        }
        issuesModel.clear(); f.forEach { issuesModel.addElement(it) }
    }
    private fun priorityOrder(p: String?): Int = when (p?.lowercase()) {
        "highest", "critical" -> 1; "high" -> 2; "medium" -> 3; "low" -> 4; "lowest" -> 5; else -> 99
    }

    private fun loadIssueDetails(issueKey: String) {
        issueTitleLabel.text = issueKey; issueStatusLabel.text = ""; estimateLabel.text = ""; descPane.text = "<html><body>Loading...</body></html>"; commentsModel.clear(); worklogsModel.clear()
        runBg(onError = { runUi { descPane.text = "<html><body>Error: ${it.message}</body></html>" } }) {
            val api = service<JiraService>().apiFromSettings(); val issue = api.getIssueDetails(issueKey); val cs = api.getComments(issueKey); val wls = api.getWorklogs(issueKey)
            val descHtml = try { api.getIssueDescriptionHtml(issueKey) } catch (_: Throwable) { "<html><body>${issue.description ?: ""}</body></html>" }
            runUi {
                currentIssue = issue
                issueTitleLabel.text = "<html><b>${issue.key}</b> — ${issue.summary}</html>"
                val sc = when (issue.statusCategory) { "done" -> "#1B7F3A"; "indeterminate" -> "#0052CC"; else -> "#888888" }
                issueStatusLabel.text = if (issue.status != null) "<html><span style='color:$sc'><b>${issue.status}</b></span></html>" else ""
                val orig = issue.originalEstimateSeconds; val rem = issue.remainingEstimateSeconds
                estimateLabel.text = when { orig != null && rem != null -> "Est: ${formatSecondsShort(orig)}  Rem: ${formatSecondsShort(rem)}"; orig != null -> "Est: ${formatSecondsShort(orig)}"; else -> "<html><span style='color:#F9A825'><b>⚠ No estimate</b></span></html>" }

                // Build HTML description with subtasks, links, attachments already included from API
                descPane.text = descHtml; descPane.caretPosition = 0

                commentsModel.clear(); cs.takeLast(MAX_COMMENTS).reversed().forEach { commentsModel.addElement(it) }
                worklogsModel.clear(); wls.takeLast(MAX_WORKLOGS).reversed().forEach { worklogsModel.addElement(it) }
                openInBrowserBtn.isEnabled = true; copyLinkBtn.isEnabled = true; setEstimateBtn.isEnabled = true; changeStatusBtn.isEnabled = true
            }
        }
    }
    private fun refreshFields(issueKey: String) {
        fieldsPane.text = "<html><body><i>Loading...</i></body></html>"
        runBg(onError = { runUi { fieldsPane.text = "<html><body>Error: ${it.message}</body></html>" } }) {
            val fields = service<JiraService>().apiFromSettings().getAllIssueFields(issueKey)
            val rows = fields.joinToString("") { (k, v) ->
                if (v.isEmpty()) {
                    // Separator row
                    "<tr><td colspan='2' style='padding:8px 4px;font-weight:bold;color:#1B7F3A;border-bottom:2px solid #1B7F3A'>$k</td></tr>"
                } else {
                    val escaped = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    val linked = escaped.replace(Regex("(https?://[^\\s<,)]+)")) { "<a href='${it.value}'>${it.value}</a>" }
                    val wrapped = linked.replace("\n", "<br/>")
                    "<tr><td style='padding:4px 8px;vertical-align:top;white-space:nowrap;color:#888888;font-weight:bold;border-bottom:1px solid #3C3F41;width:160px'>$k</td>" +
                    "<td style='padding:4px 8px;border-bottom:1px solid #3C3F41;word-wrap:break-word'>$wrapped</td></tr>"
                }
            }
            val html = "<html><body style='font-family:sans-serif;font-size:12px;margin:0;padding:0'>" +
                "<table cellspacing='0' cellpadding='0' width='100%'>$rows</table></body></html>"
            runUi { fieldsPane.text = html; fieldsPane.caretPosition = 0 }
        }
    }
    private fun refreshComments(issueKey: String) {
        runBg(onError = {}) {
            val cs = try { service<JiraService>().apiFromSettings().getComments(issueKey) } catch (_: Throwable) { return@runBg }
            runUi { commentsModel.clear(); cs.takeLast(MAX_COMMENTS).reversed().forEach { commentsModel.addElement(it) } }
        }
    }
    private fun refreshWorklogs(issueKey: String) {
        runBg(onError = {}) {
            val wls = try { service<JiraService>().apiFromSettings().getWorklogs(issueKey) } catch (_: Throwable) { return@runBg }
            runUi { worklogsModel.clear(); wls.takeLast(MAX_WORKLOGS).reversed().forEach { worklogsModel.addElement(it) } }
        }
    }
    private fun openBrowser() { val i = currentIssue ?: return; val p = service<JiraProfilesState>().activeProfile() ?: return; val u = "${p.baseUrl.trimEnd('/')}/browse/${i.key}"; try { Desktop.getDesktop().browse(URI(u)) } catch (_: Throwable) { cp(u) } }
    private fun copyLink() { val i = currentIssue ?: return; val p = service<JiraProfilesState>().activeProfile() ?: return; val u = "${p.baseUrl.trimEnd('/')}/browse/${i.key}"; cp(u); setStatus(false, "Copied: $u") }
    private fun openSetEstimate() {
        val i = currentIssue ?: return; val d = SetEstimateDialog(project, i.key, i.originalEstimateSeconds); if (!d.showAndGet()) return
        setStatus(true, "Setting estimate...")
        runBg(onError = { setStatus(false, "Error: ${it.message}") }) { service<JiraService>().apiFromSettings().setEstimate(i.key, d.originalEstimateSeconds); runUi { setStatus(false, "Estimate set"); loadIssueDetails(i.key) } }
    }
    private fun openChangeStatus() {
        val i = currentIssue ?: return; setStatus(true, "Loading transitions...")
        runBg(onError = { setStatus(false, "Error: ${it.message}") }) { val ts = service<JiraService>().apiFromSettings().getTransitions(i.key); runUi { setStatus(false, ""); if (ts.isEmpty()) { setStatus(false, "No transitions available"); return@runUi }; val d = TransitionPickerDialog(project, i.key, ts, i.status); if (d.showAndGet()) d.selectedTransition?.let { applyTransition(i.key, it) } } }
    }
    private fun openAddWorklog() {
        val i = issuesList.selectedValue ?: return; val d = AddWorklogDialog(project, i.key); if (!d.showAndGet()) return
        setStatus(true, "Adding worklog...")
        runBg(onError = { setStatus(false, "Error: ${it.message}") }) { service<JiraService>().apiFromSettings().addWorklog(i.key, d.started, d.timeSpentSeconds, d.comment.ifBlank { null }); runUi { setStatus(false, "Worklog added (${formatSecondsShort(d.timeSpentSeconds)})"); loadIssueDetails(i.key) } }
    }
    private fun submitComment() {
        val i = issuesList.selectedValue ?: return; val t = commentInput.text.trim().ifBlank { return }; if (!commentInFlight.compareAndSet(false, true)) return
        addCommentBtn.isEnabled = false; commentInput.text = ""; setStatus(true, "Saving comment...")
        runBg(onError = { runUi { setStatus(false, "Error: ${it.message}"); addCommentBtn.isEnabled = true }; commentInFlight.set(false) }) {
            service<JiraService>().apiFromSettings().addComment(i.key, t); runUi { setStatus(false, "Comment added"); addCommentBtn.isEnabled = true; commentsModel.add(0, JiraComment("local-${System.currentTimeMillis()}", null,null,null,null,"You", OffsetDateTime.now(), t)); commentsList.ensureIndexIsVisible(0) }; commentInFlight.set(false)
        }
    }
    private fun submitMr() {
        val i = issuesList.selectedValue ?: return; val mr = mrInput.text.trim().ifBlank { return }; if (!mrInFlight.compareAndSet(false, true)) return
        saveMrBtn.isEnabled = false; setStatus(true, "Saving MR...")
        runBg(onError = { runUi { setStatus(false, "Error: ${it.message}"); saveMrBtn.isEnabled = true }; mrInFlight.set(false) }) {
            service<JiraService>().apiFromSettings().addComment(i.key, "Merge Request: $mr"); runUi {
                setStatus(false, "MR saved"); saveMrBtn.isEnabled = true; mrInput.text = ""
                commentsModel.add(0, JiraComment("local-mr-${System.currentTimeMillis()}", null,null,null,null,"You", OffsetDateTime.now(), "Merge Request: $mr"))
                commentsList.ensureIndexIsVisible(0)
            }; mrInFlight.set(false)
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    private fun renderTracking(s: TrackingSnapshot) {
        timerLabel.text = formatSeconds(s.currentIssueTrackedSeconds)
        timerLabel.foreground = if (s.running) JBColor(Color(0x1B7F3A), Color(0x4CAF50)) else gray()
        todayIssueLabel.text = "Issue: ${formatSecondsShort(s.currentIssueTrackedSeconds)}"
        todayTotalLabel.text = "Total: ${formatSecondsShort(s.totalTodaySeconds)}"
        toolbarTodayLabel.text = "Today: ${formatSecondsShort(s.totalTodaySeconds)}"
        todayStatsArea.text = s.todayByIssueSeconds.entries.sortedByDescending { it.value }.joinToString("\n") { "  ${it.key}  ->  ${formatSecondsShort(it.value)}" }.ifBlank { "No time logged today yet" }
        refreshButtons(s)
    }
    private fun refreshButtons(s: TrackingSnapshot) {
        startStopBtn.isEnabled = s.running || issuesList.selectedValue != null
        addWorklogBtn.isEnabled = issuesList.selectedValue != null
        startStopBtn.text = if (s.running) "Stop" else "Start"
        startStopBtn.foreground = if (s.running) JBColor(Color(0xC62828), Color(0xEF5350)) else JBColor(Color(0x1B7F3A), Color(0x4CAF50))
    }
    private fun refreshProfileLabel() {
        val p = service<JiraProfilesState>().activeProfile()
        if (p != null) { profileLabel.text = p.name; profileLabel.foreground = JBColor(Color(0x1B7F3A), Color(0x4CAF50)) }
        else { profileLabel.text = "No profile"; profileLabel.foreground = gray() }
    }
    private fun setStatus(loading: Boolean, message: String) { runUi {
        loadingIcon.isVisible = loading; statusLabel.text = message
        statusLabel.foreground = when { loading -> gray(); message.startsWith("Error") || message.startsWith("No active") -> JBColor(Color(0xC62828), Color(0xEF5350)); else -> JBColor(Color(0x1B7F3A), Color(0x4CAF50)) }
    } }
    private fun cp(text: String) { CopyPasteManager.getInstance().setContents(StringSelection(text)) }

    private fun loadWorklogHistory() {
        historyModel.clear()
        setStatus(true, "Loading history...")
        runUi { historyProgressLabel.text = "Loading..."; historyProgressLabel.isVisible = true }
        runBg(onError = { runUi { historyProgressLabel.isVisible = false }; setStatus(false, "Error: ${it.message}") }) {
            val api = service<JiraService>().apiFromSettings()
            val me = api.getMyself()
            val p = projectCombo.selectedItem as? JiraProject
            if (p == null) { runUi { setStatus(false, "Select a project first"); historyProgressLabel.isVisible = false }; return@runBg }
            val issues = api.searchMyIssues(p.key, maxResults = 100)
            val dayDetails = java.util.TreeMap<java.time.LocalDate, MutableMap<String, Int>>(compareByDescending { it })
            var processed = 0
            for (issue in issues) {
                processed++
                val pct = processed
                val total = issues.size
                runUi { historyProgressLabel.text = "Loading worklogs... $pct / $total issues"; setStatus(true, "Loading... ($pct/$total issues)") }
                try {
                    val wls = api.getWorklogs(issue.key, maxResults = 500)
                    for (wl in wls) {
                        val isMe = (!me.accountId.isNullOrBlank() && wl.authorAccountId == me.accountId) ||
                            (!me.displayName.isNullOrBlank() && wl.authorDisplayName == me.displayName) ||
                            (!me.key.isNullOrBlank() && wl.authorKey == me.key) ||
                            (!me.name.isNullOrBlank() && wl.authorName == me.name)
                        if (!isMe) continue
                        val day = wl.started?.toLocalDate() ?: continue
                        dayDetails.getOrPut(day) { mutableMapOf() }.let { it[issue.key] = (it[issue.key] ?: 0) + wl.timeSpentSeconds }
                    }
                } catch (_: Throwable) {}
            }
            val entries = mutableListOf<HistoryEntry>()
            var weekTotal = 0; var lastWeek = -1
            for ((day, issueMap) in dayDetails) {
                val weekNum = day.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                if (lastWeek != -1 && weekNum != lastWeek) {
                    entries.add(HistoryEntry.WeekSeparator(weekTotal)); weekTotal = 0
                }
                lastWeek = weekNum
                val dayTotal = issueMap.values.sum(); weekTotal += dayTotal
                entries.add(HistoryEntry.DayEntry(day, dayTotal, issueMap.entries.sortedByDescending { it.value }.map { it.key to it.value }))
            }
            if (weekTotal > 0) entries.add(HistoryEntry.WeekSeparator(weekTotal))
            val grandTotal = dayDetails.values.sumOf { it.values.sum() }
            entries.add(HistoryEntry.GrandTotal(grandTotal, dayDetails.size))
            runUi { historyModel.clear(); entries.forEach { historyModel.addElement(it) }; historyProgressLabel.isVisible = false; setStatus(false, "History loaded (${dayDetails.size} days)") }
        }
    }
    private fun loadActivity() {
        activityModel.clear()
        runUi { activityProgressLabel.text = "Loading..."; activityProgressLabel.isVisible = true }
        setStatus(true, "Loading activity...")
        runBg(onError = { runUi { activityProgressLabel.isVisible = false }; setStatus(false, "Error: ${it.message}") }) {
            val p = projectCombo.selectedItem as? JiraProject
            if (p == null) { runUi { setStatus(false, "Select a project first"); activityProgressLabel.isVisible = false }; return@runBg }
            val activities = service<JiraService>().apiFromSettings().getRecentActivity(p.key, hoursBack = 24)
            runUi {
                activityModel.clear()
                activities.forEach { activityModel.addElement(it) }
                activityProgressLabel.isVisible = false
                setStatus(false, "Activity: ${activities.size} events (last 24h)")
            }
        }
    }
    // ── Util ──────────────────────────────────────────────────────────────────
    private fun gray() = JBColor(Color(0x888888), Color(0x888888))
    private fun runBg(onError: (Throwable) -> Unit, block: () -> Unit) { ApplicationManager.getApplication().executeOnPooledThread { try { block() } catch (t: Throwable) { runUi { onError(t) } } } }
    private fun runUi(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else ApplicationManager.getApplication().invokeLater(block, ModalityState.any()) }
    private fun roArea() = JBTextArea().apply { lineWrap=true; wrapStyleWord=true; isEditable=false; border=JBUI.Borders.empty(8); background=JBColor.PanelBackground }
    private fun sp(c: Component) = JBScrollPane(c).apply { border=JBUI.Borders.empty() }
    private fun ib(icon: Icon, tooltip: String) = JButton(icon).apply { toolTipText=tooltip; isFocusable=false; isBorderPainted=false; isContentAreaFilled=false; preferredSize=Dimension(28,28) }
}
