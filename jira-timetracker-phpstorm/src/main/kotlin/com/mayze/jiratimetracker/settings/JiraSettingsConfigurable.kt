package com.mayze.jiratimetracker.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page under Tools → Jira Time Tracker.
 * Profiles are managed via ManageProfilesDialog (also accessible from the tool window).
 */
class JiraSettingsConfigurable : SearchableConfigurable {
    override fun getDisplayName(): String = "Jira Time Tracker"
    override fun getId(): String = "com.mayze.jiratimetracker.settings"
    override fun isModified(): Boolean = false
    override fun apply() {}

    override fun createComponent(): JComponent {
        val openBtn = JButton("Manage Jira Profiles…")
        openBtn.addActionListener {
            ManageProfilesDialog(null).showAndGet()
        }

        return JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(16)
            add(JBLabel("<html><b>Jira Time Tracker</b><br/>" +
                "Profiles are stored per-IDE. Tokens are kept in IDE PasswordSafe.<br/><br/>" +
                "You can also manage profiles directly from the Jira tool window (⚙ button).</html>"
            ).apply { font = font.deriveFont(Font.PLAIN, 12f) }, BorderLayout.NORTH)
            add(openBtn, BorderLayout.CENTER)
        }
    }
}
