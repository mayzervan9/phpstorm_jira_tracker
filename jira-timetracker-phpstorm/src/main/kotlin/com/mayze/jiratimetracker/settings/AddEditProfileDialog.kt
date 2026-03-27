package com.mayze.jiratimetracker.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Dialog for creating or editing a single Jira profile.
 * Pass [existing] to edit, null to create new.
 */
class AddEditProfileDialog(
    project: Project?,
    private val existing: JiraProfile? = null
) : DialogWrapper(project, true) {

    private val nameField = JBTextField()
    private val baseUrlField = JBTextField()
    private val emailField = JBTextField()
    private val tokenField = JBPasswordField()
    private val isCloudBox = JBCheckBox("Jira Cloud (API token). Uncheck for Server/DC.", true)
    private val intervalField = JBTextField("5")

    init {
        title = if (existing == null) "Add Jira Profile" else "Edit Jira Profile"
        if (existing != null) loadFrom(existing)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Profile name:", nameField, 1, false)
            .addSeparator()
            .addLabeledComponent("Base URL:", baseUrlField, 1, false)
            .addLabeledComponent("Email / Username:", emailField, 1, false)
            .addLabeledComponent("API token / Password:", tokenField, 1, false)
            .addComponent(javax.swing.JLabel("<html><small><a href='https://id.atlassian.com/manage-profile/security/api-tokens'>Get API token for Jira Cloud</a></small></html>").apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) { try { java.awt.Desktop.getDesktop().browse(java.net.URI("https://id.atlassian.com/manage-profile/security/api-tokens")) } catch (_: Throwable) {} }
                })
            })
            .addComponent(isCloudBox)
            .addSeparator()
            .addLabeledComponent("Update interval (min):", intervalField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val hint = if (existing != null)
            "<html><small>Leave token blank to keep the existing one.</small></html>"
        else
            "<html><small>Token is stored securely in IDE PasswordSafe.</small></html>"

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(4)
            preferredSize = Dimension(420, 300)
            add(JLabel(hint), BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Profile name is required", nameField)
        if (baseUrlField.text.isBlank()) return ValidationInfo("Base URL is required", baseUrlField)
        if (emailField.text.isBlank()) return ValidationInfo("Email / Username is required", emailField)
        if (existing == null && String(tokenField.password).isBlank())
            return ValidationInfo("Token / Password is required", tokenField)
        return null
    }

    /** Call after isOK to persist the profile */
    fun applyToState() {
        val state = service<JiraProfilesState>()
        val profile = existing ?: JiraProfile()

        profile.name = nameField.text.trim()
        profile.baseUrl = baseUrlField.text.trim().trimEnd('/')
        profile.emailOrUsername = emailField.text.trim()
        profile.isCloud = isCloudBox.isSelected
        profile.updateIntervalMinutes = intervalField.text.toIntOrNull()?.coerceIn(1, 60) ?: 5

        val typedToken = String(tokenField.password)
        if (typedToken.isNotBlank()) state.saveToken(profile.id, typedToken)

        if (existing == null) state.addProfile(profile)
    }

    private fun loadFrom(p: JiraProfile) {
        nameField.text = p.name
        baseUrlField.text = p.baseUrl
        emailField.text = p.emailOrUsername
        isCloudBox.isSelected = p.isCloud
        intervalField.text = p.updateIntervalMinutes.toString()
        tokenField.text = "" // never show stored token
    }
}
