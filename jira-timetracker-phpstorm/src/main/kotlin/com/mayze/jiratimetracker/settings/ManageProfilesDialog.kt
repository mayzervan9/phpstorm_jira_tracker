package com.mayze.jiratimetracker.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ManageProfilesDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<JiraProfile>()
    private val profileList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ProfileCellRenderer()
    }

    private val addBtn = JButton("Add", AllIcons.General.Add)
    private val editBtn = JButton("Edit", AllIcons.Actions.Edit)
    private val deleteBtn = JButton("Delete", AllIcons.General.Remove)
    private val selectBtn = JButton("Use this profile", AllIcons.Actions.Execute).apply {
        font = font.deriveFont(Font.BOLD)
    }

    /** The profile chosen to become active, set when user clicks "Use this profile" */
    var chosenProfileId: String? = null
        private set

    init {
        title = "Jira Profiles"
        setOKButtonText("Close")
        // No cancel button
        reload()
        updateButtons()
        init()
    }

    override fun createCenterPanel(): JComponent {
        profileList.addListSelectionListener { updateButtons() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(addBtn); add(editBtn); add(deleteBtn)
        }

        addBtn.addActionListener { onAdd() }
        editBtn.addActionListener { onEdit() }
        deleteBtn.addActionListener { onDelete() }
        selectBtn.addActionListener { onSelect() }

        val bottom = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(selectBtn)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(480, 320)
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(profileList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    // No cancel button needed
    override fun createActions() = arrayOf(okAction)

    private fun onAdd() {
        val dlg = AddEditProfileDialog(project)
        if (dlg.showAndGet()) {
            dlg.applyToState()
            reload()
            // auto-select if first profile
            val state = service<JiraProfilesState>()
            if (state.profiles.size == 1) {
                state.activeProfileId = state.profiles.first().id
                chosenProfileId = state.activeProfileId
            }
        }
    }

    private fun onEdit() {
        val profile = profileList.selectedValue ?: return
        val dlg = AddEditProfileDialog(project, existing = profile)
        if (dlg.showAndGet()) {
            dlg.applyToState()
            reload()
        }
    }

    private fun onDelete() {
        val profile = profileList.selectedValue ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete profile \"${profile.name}\"? The stored token will also be removed.",
            "Delete Profile",
            Messages.getWarningIcon()
        )
        if (confirm == Messages.YES) {
            service<JiraProfilesState>().removeProfile(profile.id)
            if (chosenProfileId == profile.id) chosenProfileId = null
            reload()
        }
    }

    private fun onSelect() {
        val profile = profileList.selectedValue ?: return
        service<JiraProfilesState>().activeProfileId = profile.id
        chosenProfileId = profile.id
        reload() // refresh checkmarks
    }

    private fun reload() {
        val state = service<JiraProfilesState>()
        listModel.clear()
        state.profiles.forEach { listModel.addElement(it) }
        updateButtons()
    }

    private fun updateButtons() {
        val hasSelection = profileList.selectedValue != null
        editBtn.isEnabled = hasSelection
        deleteBtn.isEnabled = hasSelection
        selectBtn.isEnabled = hasSelection
    }

    // ── Cell renderer ─────────────────────────────────────────────────────────

    private inner class ProfileCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val profile = value as? JiraProfile ?: return this
            val state = service<JiraProfilesState>()
            val isActive = profile.id == state.activeProfileId

            val activeMarker = if (isActive) " ✓" else ""
            val type = if (profile.isCloud) "Cloud" else "Server/DC"
            text = "<html><b>${profile.name}$activeMarker</b>" +
                "  <span style='color:gray'>${profile.baseUrl}  ·  ${profile.emailOrUsername}  ·  $type</span></html>"
            icon = if (isActive) AllIcons.Actions.Execute else AllIcons.General.Web
            border = JBUI.Borders.empty(6, 8)

            if (isActive && !isSelected) {
                foreground = JBColor(Color(0x1B7F3A), Color(0x4CAF50))
            }
            return this
        }
    }
}
