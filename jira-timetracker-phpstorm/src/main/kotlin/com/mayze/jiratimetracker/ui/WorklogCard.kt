package com.mayze.jiratimetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.jira.JiraWorklog
import com.mayze.jiratimetracker.util.formatSecondsShort
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class WorklogListCellRenderer : ListCellRenderer<JiraWorklog> {
    override fun getListCellRendererComponent(
        list: JList<out JiraWorklog>, value: JiraWorklog?,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val wl = value ?: return JPanel()
        return WorklogCardPanel(wl, isSelected, list)
    }
}

private class WorklogCardPanel(
    wl: JiraWorklog,
    isSelected: Boolean,
    list: JList<*>
) : JPanel(BorderLayout(8, 0)) {

    init {
        val author = wl.authorDisplayName ?: "?"
        val date = wl.started?.toLocalDateTime()
            ?.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")) ?: ""
        val duration = formatSecondsShort(wl.timeSpentSeconds)

        isOpaque = true
        background = if (isSelected) list.selectionBackground
        else JBColor(Color(0xF8F8F8), Color(0x2B2D30))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(Color(0xE0E0E0), Color(0x3C3F41)), 0, 0, 1, 0),
            JBUI.Borders.empty(10, 10, 10, 12)
        )

        // Avatar
        val avatar = AvatarLabel(author, isSelected)
        avatar.preferredSize = Dimension(32, 32)
        add(avatar, BorderLayout.WEST)

        // Content
        val content = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(4)
        }

        // Duration badge
        val badge = JLabel(duration).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = Color.WHITE
            background = JBColor(Color(0x1B7F3A), Color(0x2E7D32))
            isOpaque = true
            border = JBUI.Borders.empty(2, 6)
        }

        val header = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(JLabel(author).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = if (isSelected) list.selectionForeground
                else JBColor(Color(0x1A1A1A), Color(0xDDDDDD))
            }, BorderLayout.WEST)
            add(JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(badge, BorderLayout.WEST)
                add(JLabel(date).apply {
                    font = font.deriveFont(Font.PLAIN, 10f)
                    foreground = if (isSelected) list.selectionForeground
                    else JBColor(Color(0x888888), Color(0x888888))
                    border = JBUI.Borders.emptyLeft(6)
                }, BorderLayout.CENTER)
            }, BorderLayout.EAST)
        }

        val commentText = wl.comment.orEmpty().trim()
        val body = if (commentText.isNotBlank()) {
            JLabel("<html><div style='width:380px'>${
                commentText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\n", "<br/>")
            }</div></html>").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = if (isSelected) list.selectionForeground
                else JBColor(Color(0x333333), Color(0xBBBBBB))
                border = JBUI.Borders.emptyTop(2)
            }
        } else null

        content.add(header, BorderLayout.NORTH)
        if (body != null) content.add(body, BorderLayout.CENTER)
        add(content, BorderLayout.CENTER)
    }
}
