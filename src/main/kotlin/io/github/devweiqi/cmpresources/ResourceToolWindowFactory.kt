package io.github.devweiqi.cmpresources

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Future
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import kotlin.io.path.extension
import kotlin.io.path.name

private val LOG = Logger.getInstance("CMP Resource Manager")

class ResourceToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ResourcePanel(project = project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.search
        toolWindow.contentManager.addContent(content)
        panel.refresh()
    }
}

private class ResourcePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    val search = JBTextField()
    private val roots = JComboBox<String>()
    private val tabs = JBTabsFactory.createTabs(project, this)
    private val groupModel = DefaultListModel<ResourceGroup>()
    private val groups = JBList(groupModel)
    private val versionModel = DefaultListModel<Resource>()
    private val versions = JBList(versionModel)
    private val pages = CardLayout()
    private val body = JPanel(pages)
    private val title = JLabel()
    private val status = JLabel("Scanning composeResources…")
    private val preview = JButton("Preview")
    private val play = JButton("Play WebP")
    private val refreshButton = iconButton(icon = AllIcons.Actions.Refresh, label = "Refresh resources")
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("CMP Resource Manager", 2)
    private val thumbnails = object : LinkedHashMap<Pair<Path, Int>, Icon>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Path, Int>, Icon>): Boolean = size > 256
    }
    private val pending = mutableSetOf<Pair<Path, Int>>()
    private var resources = emptyList<Resource>()
    private var rootPaths = emptyList<Path>()
    private var rootCounts = emptyMap<Path, Int>()
    private var scan: Future<*>? = null
    private var openedGroup: ResourceGroup? = null
    private val changes = ResourceChangeListener(roots = ::scanPaths, refresh = { refresh() })

    @Volatile private var disposed = false
    private var updating = false
    private var generation = 0
    private var warningCount = 0

    init {
        title.putClientProperty("html.disable", true)
        roots.preferredSize = JBUI.size(190, 28)
        roots.minimumSize = JBUI.size(90, 28)
        roots.accessibleContext.accessibleName = "Resource source root"
        roots.toolTipText = "Compose resource module and source set"
        search.emptyText.text = "Search resources…"
        search.accessibleContext.accessibleName = "Search resources"
        val toolbar = JPanel(BorderLayout(JBUI.scale(6), 0))
        toolbar.border = JBUI.Borders.empty(6, 8)
        toolbar.add(roots, BorderLayout.WEST)
        toolbar.add(search, BorderLayout.CENTER)
        toolbar.add(refreshButton, BorderLayout.EAST)
        add(toolbar, BorderLayout.NORTH)

        groups.selectionMode = ListSelectionModel.SINGLE_SELECTION
        groups.border = JBUI.Borders.empty(0, 8)
        groups.setExpandableItemsEnabled(false)
        groups.accessibleContext.accessibleName = "Resources grouped by name"
        groups.cellRenderer = ResourceGroupRenderer(thumbnail = ::thumbnail, heading = ::groupHeading)
        val groupScroll = JBScrollPane(groups)
        groupScroll.border = JBUI.Borders.empty()
        body.add(groupScroll, "groups")

        versions.selectionMode = ListSelectionModel.SINGLE_SELECTION
        versions.layoutOrientation = JList.HORIZONTAL_WRAP
        versions.visibleRowCount = -1
        versions.fixedCellWidth = JBUI.scale(184)
        versions.fixedCellHeight = JBUI.scale(218)
        versions.border = JBUI.Borders.empty(6)
        versions.setExpandableItemsEnabled(false)
        versions.accessibleContext.accessibleName = "Resource versions"
        versions.cellRenderer = ResourceVersionRenderer(thumbnail = ::thumbnail)
        val detail = JPanel(BorderLayout())
        val breadcrumb = JPanel(BorderLayout(JBUI.scale(6), 0))
        breadcrumb.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 8))
        val back = iconButton(icon = AllIcons.Actions.Back, label = "Back to resources")
        back.addActionListener { showGroups() }
        breadcrumb.add(back, BorderLayout.WEST)
        breadcrumb.add(title, BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(4), 0))
        preview.addActionListener { openSelected() }
        play.addActionListener { versions.selectedValue?.let { playWebp(project = project, path = it.file) } }
        actions.add(preview)
        actions.add(play)
        breadcrumb.add(actions, BorderLayout.EAST)
        detail.add(breadcrumb, BorderLayout.NORTH)
        val versionScroll = JBScrollPane(versions)
        versionScroll.border = JBUI.Borders.empty()
        detail.add(versionScroll, BorderLayout.CENTER)
        body.add(detail, "versions")
        versions.addListSelectionListener { updateActions() }
        installResourcePopup(project = project, list = groups, resources = { it.versions }, refresh = ::refresh, openSource = ::openSource, isResourceRow = { index, point ->
            groupHeading(index = index, root = groupModel[index].root) == null || point.y >= groups.getCellBounds(index, index).y + JBUI.scale(40)
        })
        installResourcePopup(project = project, list = versions, resources = { listOf(it) }, refresh = ::refresh, openSource = ::openSource)

        listOf("drawable", "string", "string-array", "plurals", "font", "files").forEach { type ->
            tabs.addTab(TabInfo(JPanel(BorderLayout())).setText(resourceTypeLabel(type = type)).setObject(type))
        }
        tabs.presentation.setSingleRow(true).setSupportsCompression(true).setTabDraggingEnabled(false)
        tabs.addListener(
            object : TabsListener {
                override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                    newSelection?.component?.add(body, BorderLayout.CENTER)
                    filter()
                }
            },
            this
        )
        tabs.selectedInfo?.component?.add(body, BorderLayout.CENTER)
        add(tabs.component, BorderLayout.CENTER)
        status.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(5, 10))
        status.foreground = JBColor.GRAY
        add(status, BorderLayout.SOUTH)

        installResourceActivation(
            list = groups,
            isResourceRow = { index, event ->
                groupHeading(index = index, root = groupModel[index].root) == null ||
                    event.y >= groups.getCellBounds(index, index).y + JBUI.scale(40)
            },
            activate = { showVersions(group = it) }
        )
        versions.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val index = versions.locationToIndex(event.point)
                if (SwingUtilities.isLeftMouseButton(event) && !event.isControlDown && !event.isPopupTrigger && event.clickCount == 2 && index >= 0 && versions.getCellBounds(index, index).contains(event.point)) openSelected()
            }
        })
        groups.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "versions")
        groups.actionMap.put(
            "versions",
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    groups.selectedValue?.let { showVersions(group = it) }
                }
            }
        )
        versions.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "openResource")
        versions.actionMap.put(
            "openResource",
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = openSelected()
            }
        )
        body.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("alt LEFT"), "back")
        body.actionMap.put(
            "back",
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = showGroups()
            }
        )
        roots.addActionListener { filter() }
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = filter()
        })
        refreshButton.addActionListener { refresh() }
        Disposer.register(this, changes)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, changes)
        project.messageBus.connect(this).subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = changes.requestRefresh()
            }
        )
    }

    fun refresh() {
        if (disposed || project.isDisposed) return
        changes.cancelPending()
        scan?.cancel(true)
        val requestedGeneration = ++generation
        thumbnails.clear()
        pending.clear()
        refreshButton.isEnabled = false
        status.text = "Scanning composeResources…"
        val paths = scanPaths()
        scan = executor.submit {
            try {
                val snapshot = scanResources(roots = paths)
                later {
                    if (requestedGeneration != generation) return@later
                    val selectedRoot = rootPaths.getOrNull(index = roots.selectedIndex - 1)
                    resources = snapshot.resources
                    warningCount = snapshot.warnings.size
                    snapshot.warnings.forEach { LOG.warn(it) }
                    updating = true
                    roots.removeAllItems()
                    roots.addItem("Module: All")
                    rootPaths = resources.map { it.root }.distinct().sorted()
                    rootPaths.forEach { roots.addItem(rootLabel(root = it)) }
                    roots.selectedIndex = rootPaths.indexOf(element = selectedRoot) + 1
                    updating = false
                    refreshButton.isEnabled = true
                    filter(preserveSelection = true)
                }
            } catch (exception: IOException) {
                if (Thread.currentThread().isInterrupted) return@submit
                LOG.warn("Unable to scan Compose resources", exception)
                later {
                    if (requestedGeneration != generation) return@later
                    refreshButton.isEnabled = true
                    status.text = "Scan failed. Press Refresh to retry."
                }
            }
        }
    }

    private fun scanPaths(): List<Path> {
        if (disposed || project.isDisposed) return emptyList()
        val candidates = listOfNotNull(project.basePath?.let(Path::of)) +
            ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.map { Path.of(it.path) }
        return candidates.distinct().filter { path -> candidates.none { other -> other != path && path.startsWith(other) } }
    }

    private fun rootLabel(root: Path): String {
        val base = project.basePath?.let(Path::of)
        return if (base != null && root.startsWith(base)) {
            base.relativize(root).map { it.toString() }.filter { it != "src" && it != "composeResources" }.joinToString(separator = ".")
        } else {
            root.toString()
        }
    }

    private fun groupHeading(index: Int, root: Path): String? =
        if (index <= 0 || groupModel[index - 1].root != root) "${rootLabel(root = root)} (${rootCounts[root] ?: 0})" else null

    private fun filter(preserveSelection: Boolean = false) {
        if (updating) return
        val selectedGroup = groups.selectedValue?.identity
        val opened = openedGroup?.identity.takeIf { preserveSelection }
        val selectedFile = versions.selectedValue?.file
        val type = tabs.selectedInfo?.`object` as? String ?: "drawable"
        val root = rootPaths.getOrNull(index = roots.selectedIndex - 1)
        val filtered = groupResources(resources = resources.filter { it.type == type && (root == null || it.root == root) })
            .filter { group -> group.versions.any { it.matches(query = search.text.trim(), type = type, folder = "All qualifiers") } }
        rootCounts = filtered.groupingBy { it.root }.eachCount()
        groupModel.clear()
        filtered.forEach(groupModel::addElement)
        groups.selectedIndex = filtered.indexOfFirst { it.identity == selectedGroup }
        groups.emptyText.text = if (resources.isEmpty()) "No composeResources found. Resources appear automatically after saving." else "No matching resources"
        roots.toolTipText = root?.toString() ?: "All Compose resource roots"
        val restored = filtered.firstOrNull { it.identity == opened }
        if (restored != null) {
            showVersions(group = restored, selectedFile = selectedFile, requestFocus = false)
        } else {
            showGroups(requestFocus = false)
        }
    }

    private fun showGroups(requestFocus: Boolean = true) {
        openedGroup = null
        pages.show(body, "groups")
        status.text = "${groupModel.size()} resources" + warningSuffix()
        if (requestFocus) groups.requestFocusInWindow()
    }

    private fun showVersions(group: ResourceGroup, selectedFile: Path? = null, requestFocus: Boolean = true) {
        openedGroup = group
        title.text = group.name
        title.toolTipText = group.root.toString()
        versionModel.clear()
        val textResource = group.versions.first().value != null
        versions.cellRenderer = if (textResource) ResourceValueRenderer() else ResourceVersionRenderer(thumbnail = ::thumbnail)
        versions.layoutOrientation = if (textResource) JList.VERTICAL else JList.HORIZONTAL_WRAP
        versions.fixedCellWidth = if (textResource) -1 else JBUI.scale(184)
        versions.fixedCellHeight = if (textResource) -1 else JBUI.scale(218)
        group.versions.forEach(versionModel::addElement)
        versions.selectedIndex = group.versions.indexOfFirst { it.file == selectedFile }.coerceAtLeast(0)
        pages.show(body, "versions")
        status.text = "${group.versions.size} versions" + warningSuffix()
        updateActions()
        if (requestFocus) versions.requestFocusInWindow()
    }

    private fun warningSuffix(): String = if (warningCount == 0) "" else " · $warningCount unreadable resources (see IDE log)"

    private fun updateActions() {
        val selected = versions.selectedValue
        preview.isEnabled = selected != null
        preview.text = if (selected?.value != null) "Open source" else "Preview"
        play.isVisible = selected?.animated == true
        preview.isVisible = selected?.animated != true
        play.isEnabled = selected != null
    }

    private fun thumbnail(resource: Resource, size: Int): Icon {
        if (resource.value != null) return AllIcons.FileTypes.Text
        val key = resource.file to size
        thumbnails[key]?.let { return it }
        val fallback = if (resource.type == "drawable") AllIcons.FileTypes.Image else AllIcons.FileTypes.Any_type
        if (!disposed && pending.add(key)) {
            val requestedGeneration = generation
            executor.submit {
                val result = try {
                    loadResourcePreview(path = resource.file, size = JBUI.scale(size)) ?: fallback
                } catch (exception: IOException) {
                    LOG.debug("Cannot preview ${resource.file}", exception)
                    fallback
                } catch (exception: RuntimeException) {
                    LOG.debug("Cannot preview ${resource.file}", exception)
                    fallback
                }
                later {
                    if (requestedGeneration == generation) {
                        thumbnails[key] = result
                        pending.remove(key)
                        groups.repaint()
                        versions.repaint()
                    }
                }
            }
        }
        return fallback
    }

    private fun openSelected() {
        val selected = versions.selectedValue ?: return
        if (selected.animated) {
            playWebp(project = project, path = selected.file)
            return
        }
        if (selected.type == "drawable" && selected.file.extension.equals(other = "xml", ignoreCase = true)) {
            showVectorPreview(project = project, path = selected.file)
            return
        }
        openSource(resource = selected)
    }

    private fun openSource(resource: Resource) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resource.file)
        if (file == null) {
            status.text = "File no longer exists. Press Refresh."
        } else if (resource.value != null) {
            OpenFileDescriptor(project, file, resource.value.line - 1, 0).navigate(true)
        } else {
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun later(action: () -> Unit) {
        if (disposed || project.isDisposed) return
        ToolWindowManager.getInstance(project).invokeLater {
            if (!disposed && !project.isDisposed) action()
        }
    }

    override fun dispose() {
        disposed = true
        scan?.cancel(true)
        executor.shutdownNow()
        thumbnails.clear()
        pending.clear()
    }
}

private fun iconButton(icon: Icon, label: String): JButton = JButton(icon).apply {
    toolTipText = label
    accessibleContext.accessibleName = label
    preferredSize = JBUI.size(28, 28)
    isContentAreaFilled = false
    isBorderPainted = false
}

// Swing selects the row on mousePressed, before our mouseClicked listener runs.
fun <T> installResourceActivation(list: JList<T>, isResourceRow: (Int, MouseEvent) -> Boolean, activate: (T) -> Unit) {
    var armed: T? = null
    list.addListSelectionListener { armed = null }
    list.addFocusListener(object : FocusAdapter() {
        override fun focusLost(event: FocusEvent) {
            armed = null
        }
    })
    list.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(event) || event.isControlDown || event.isPopupTrigger) return
            val index = resourcePopupIndex(list, event.point)
            if (index < 0 || !isResourceRow(index, event)) {
                armed = null
                return
            }
            val resource = list.model.getElementAt(index)
            list.selectedIndex = index
            list.requestFocusInWindow()
            if (armed == resource) {
                armed = null
                activate(resource)
            } else {
                armed = resource
            }
        }
    })
}
