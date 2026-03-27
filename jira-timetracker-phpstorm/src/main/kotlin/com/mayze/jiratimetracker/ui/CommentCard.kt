package com.mayze.jiratimetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.jira.JiraComment
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class CommentListCellRenderer : ListCellRenderer<JiraComment> {
    override fun getListCellRendererComponent(
        list: JList<out JiraComment>, value: JiraComment?,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val comment = value ?: return JPanel()
        return CommentCardPanel(comment, isSelected, list)
    }
}

private class CommentCardPanel(
    comment: JiraComment,
    isSelected: Boolean,
    list: JList<*>
) : JPanel(BorderLayout(8, 0)) {

    init {
        val author = comment.authorDisplayName ?: "?"
        val date = comment.created?.toLocalDateTime()
            ?.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")) ?: ""

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

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel(author).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = if (isSelected) list.selectionForeground
                else JBColor(Color(0x1A1A1A), Color(0xDDDDDD))
            }, BorderLayout.WEST)
            add(JLabel(date).apply {
                font = font.deriveFont(Font.PLAIN, 10f)
                foreground = if (isSelected) list.selectionForeground
                else JBColor(Color(0x888888), Color(0x888888))
            }, BorderLayout.EAST)
        }

        val body = JLabel("<html><div style='width:420px'>${
            comment.body.ifBlank { "<i>(empty)</i>" }
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br/>")
        }</div></html>").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = if (isSelected) list.selectionForeground
            else JBColor(Color(0x333333), Color(0xBBBBBB))
            border = JBUI.Borders.emptyTop(2)
        }

        content.add(header, BorderLayout.NORTH)
        content.add(body, BorderLayout.CENTER)
        add(content, BorderLayout.CENTER)
    }
}
