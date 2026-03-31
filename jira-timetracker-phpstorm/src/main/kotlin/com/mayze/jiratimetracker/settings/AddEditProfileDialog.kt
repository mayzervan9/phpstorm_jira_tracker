package com.mayze.jiratimetracker.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane

class AddEditProfileDialog(
    project: Project?,
    private val existing: JiraProfile? = null
) : DialogWrapper(project, true) {

    private val nameField = JBTextField()
    private val baseUrlField = JBTextField()
    private val intervalField = JBTextField("5")

    // Cloud tab
    private val cloudEmailField = JBTextField()
    private val cloudTokenField = JBPasswordField()

    // Server tab
    private val serverUsernameField = JBTextField()
    private val serverPasswordField = JBPasswordField()
    private val serverPatField = JBPasswordField()

    private val authTabs = JTabbedPane()

    init {
        title = if (existing == null) "Add Jira Profile" else "Edit Jira Profile"
        init()
        if (existing != null) loadFrom(existing)
    }

    override fun createCenterPanel(): JComponent {
        // Common fields
        val common = FormBuilder.createFormBuilder()
            .addLabeledComponent("Profile name:", nameField, 1, false)
            .addLabeledComponent("Base URL:", baseUrlField, 1, false)
            .panel

        // Cloud auth tab
        val tokenLink = JLabel("<html><a href='#'>Get API token (Jira Cloud)</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    try { Desktop.getDesktop().browse(URI("https://id.atlassian.com/manage-profile/security/api-tokens")) } catch (_: Throwable) {}
                }
            })
        }
        val cloudPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Email:", cloudEmailField, 1, false)
            .addLabeledComponent("API Token:", cloudTokenField, 1, false)
            .addComponent(tokenLink)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(8) }

        // Server auth tab — two sub-options
        val serverPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            val loginPass = FormBuilder.createFormBuilder()
                .addComponent(JLabel("Option 1: Username + Password").apply { font = font.deriveFont(Font.BOLD) })
                .addLabeledComponent("Username:", serverUsernameField, 1, false)
                .addLabeledComponent("Password:", serverPasswordField, 1, false)
                .addSeparator()
                .addComponent(JLabel("Option 2: Personal Access Token (PAT)").apply { font = font.deriveFont(Font.BOLD) })
                .addLabeledComponent("PAT:", serverPatField, 1, false)
                .addComponent(JLabel("<html><small>Use either login+password OR PAT. If PAT is filled, it takes priority.</small></html>"))
                .addComponentFillVertically(JPanel(), 0)
                .panel
            add(loginPass, BorderLayout.CENTER)
        }

        authTabs.addTab("Jira Cloud", cloudPanel)
        authTabs.addTab("Server / Data Center", serverPanel)

        // Bottom
        val bottom = FormBuilder.createFormBuilder()
            .addLabeledComponent("Update interval (min):", intervalField, 1, false)
            .panel.apply { border = JBUI.Borders.empty(4, 0) }

        val hint = if (existing != null)
            "Leave credentials blank to keep existing."
        else
            "Credentials are stored securely in IDE PasswordSafe."

        return JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(4)
            preferredSize = Dimension(440, 380)
            add(JLabel(hint).apply { font = font.deriveFont(Font.PLAIN, 11f); border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 6)).apply {
                add(common, BorderLayout.NORTH)
                add(authTabs, BorderLayout.CENTER)
                add(bottom, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Profile name is required", nameField)
        val url = baseUrlField.text.trim()
        if (url.isBlank()) return ValidationInfo("Base URL is required", baseUrlField)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ValidationInfo("Base URL must start with http:// or https://", baseUrlField)
        if (existing == null) {
            if (isCloudSelected()) {
                if (cloudEmailField.text.isBlank()) return ValidationInfo("Email is required", cloudEmailField)
                if (String(cloudTokenField.password).isBlank()) return ValidationInfo("API Token is required", cloudTokenField)
            } else {
                val hasPat = String(serverPatField.password).isNotBlank()
                val hasLogin = serverUsernameField.text.isNotBlank() && String(serverPasswordField.password).isNotBlank()
                if (!hasPat && !hasLogin) return ValidationInfo("Enter username+password or PAT", serverUsernameField)
            }
        }
        return null
    }

    fun applyToState() {
        val state = service<JiraProfilesState>()
        val profile = existing ?: JiraProfile()

        profile.name = nameField.text.trim()
        profile.baseUrl = baseUrlField.text.trim().trimEnd('/')
        profile.isCloud = isCloudSelected()
        profile.updateIntervalMinutes = intervalField.text.toIntOrNull()?.coerceIn(1, 60) ?: 5

        if (isCloudSelected()) {
            profile.emailOrUsername = cloudEmailField.text.trim()
            val token = String(cloudTokenField.password)
            if (token.isNotBlank()) state.saveToken(profile.id, token)
        } else {
            val pat = String(serverPatField.password)
            if (pat.isNotBlank()) {
                // PAT mode — username can be empty
                profile.emailOrUsername = serverUsernameField.text.trim()
                state.saveToken(profile.id, pat)
            } else {
                profile.emailOrUsername = serverUsernameField.text.trim()
                val pass = String(serverPasswordField.password)
                if (pass.isNotBlank()) state.saveToken(profile.id, pass)
            }
        }

        if (existing == null) state.addProfile(profile)
    }

    private fun isCloudSelected() = authTabs.selectedIndex == 0

    private fun loadFrom(p: JiraProfile) {
        nameField.text = p.name
        baseUrlField.text = p.baseUrl
        intervalField.text = p.updateIntervalMinutes.toString()
        if (p.isCloud) {
            authTabs.selectedIndex = 0
            cloudEmailField.text = p.emailOrUsername
        } else {
            authTabs.selectedIndex = 1
            serverUsernameField.text = p.emailOrUsername
        }
    }
}
