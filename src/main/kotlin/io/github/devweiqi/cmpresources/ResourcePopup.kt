package io.github.devweiqi.cmpresources

import com.intellij.ide.CopyPasteDelegator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke

fun <T> installResourcePopup(
    project: Project,
    list: JList<T>,
    resources: (T) -> List<Resource>,
    refresh: () -> Unit,
    openSource: (Resource) -> Unit,
    isResourceRow: (Int, Point) -> Boolean = { _, _ -> true }
) {
    fun show(resource: Resource, point: Point) {
        val manager = ActionManager.getInstance()
        val file = LocalFileSystem.getInstance().findFileByNioFile(resource.file)?.takeIf { it.isValid }
        val psi = file?.let { resourcePopupPsi(project, it) }
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformDataKeys.CONTEXT_COMPONENT, list)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, file?.let { arrayOf(it) })
            .add(CommonDataKeys.PSI_FILE, psi)
            .add(CommonDataKeys.PSI_ELEMENT, psi)
            .add(PlatformDataKeys.PSI_ELEMENT_ARRAY, psi?.let { arrayOf(it) })
            .add(PlatformDataKeys.COPY_PROVIDER, CopyPasteDelegator(project, list).copyProvider)
            .build()
        val actions = DefaultActionGroup()
        actions.add(DumbAwareAction.create("Refresh Preview") { refresh() })
        actions.addSeparator()
        // Values share their XML file: keep file refactorings out of entry menus.
        if (resource.value == null) manager.getAction("Move")?.let(actions::add)
        manager.getAction("\$Copy")?.let(actions::add)
        actions.add(
            DumbAwareAction.create("Copy Value") {
                CopyPasteManager.getInstance().setContents(StringSelection(resource.value?.text ?: resource.name))
            }
        )
        actions.add(
            DumbAwareAction.create("Copy Path") {
                CopyPasteManager.getInstance().setContents(StringSelection(resource.file.toString()))
            }
        )
        if (resource.value == null) {
            manager.getAction("RenameElement")?.let(actions::add)
            manager.getAction("SafeDelete")?.let(actions::add)
        }
        actions.addSeparator()
        if (resource.value == null) manager.getAction("FindUsages")?.let(actions::add)
        manager.getAction("SelectIn")?.let(actions::add)
        manager.getAction("RevealIn")?.let(actions::add)
        actions.add(DumbAwareAction.create("Open Source") { openSource(resource) })
        manager.createActionPopupMenu("CMPResourceManager.Popup", actions).apply {
            setDataContext { context }
            component.show(list, point.x, point.y)
        }
    }

    fun showSelection(point: Point) {
        val selected = list.selectedValue ?: return
        val variants = resources(selected)
        if (variants.size == 1) {
            show(resource = variants.single(), point = point)
        } else if (variants.isNotEmpty()) {
            val actions = DefaultActionGroup()
            actions.add(DumbAwareAction.create("Refresh Preview") { refresh() })
            actions.addSeparator("Choose a version")
            variants.forEach { resource ->
                actions.add(
                    DumbAwareAction.create(resource.relativePath) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed && list.isShowing) show(resource = resource, point = point)
                        }
                    }
                )
            }
            ActionManager.getInstance().createActionPopupMenu("CMPResourceManager.Versions", actions).component.show(list, point.x, point.y)
        }
    }

    list.addMouseListener(object : MouseAdapter() {
        private fun popup(event: MouseEvent) {
            if (!event.isPopupTrigger) return
            val index = resourcePopupIndex(list = list, point = event.point)
            if (index < 0 || !isResourceRow(index, event.point)) {
                list.clearSelection()
                return
            }
            list.selectedIndex = index
            list.requestFocusInWindow()
            showSelection(point = event.point)
            event.consume()
        }

        override fun mousePressed(event: MouseEvent) = popup(event)

        override fun mouseReleased(event: MouseEvent) = popup(event)
    })
    list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift F10"), "resourcePopup")
    list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("CONTEXT_MENU"), "resourcePopup")
    list.actionMap.put(
        "resourcePopup",
        object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                val index = list.selectedIndex
                if (index < 0) return
                val bounds = list.getCellBounds(index, index)
                showSelection(point = Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2))
            }
        }
    )
}

fun resourcePopupIndex(list: JList<*>, point: Point): Int {
    val index = list.locationToIndex(point)
    return if (index >= 0 && list.getCellBounds(index, index).contains(point)) index else -1
}

fun resourcePopupPsi(project: Project, file: VirtualFile): PsiFile? = ReadAction.computeBlocking<PsiFile?, RuntimeException> {
    PsiManager.getInstance(project).findFile(file)
}
