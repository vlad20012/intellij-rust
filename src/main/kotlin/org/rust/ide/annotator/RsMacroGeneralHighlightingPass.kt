/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.RainbowVisitor
import com.intellij.codeInsight.daemon.impl.*
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager
import com.intellij.concurrency.JobLauncher
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.CommonProcessors
import gnu.trove.THashSet
import org.rust.lang.core.macros.MacroExpansion
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.macros.findElementExpandedFrom
import org.rust.lang.core.macros.findMacroCallExpandedFrom
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.ext.expansion
import org.rust.lang.core.psi.ext.startOffset
import org.rust.lang.core.resolve.DEFAULT_RECURSION_LIMIT
import java.util.*

class RsMacroGeneralHighlightingPass(
    project: Project,
    file: PsiFile,
    document: Document,
    startOffset: Int,
    endOffset: Int,
    updateAll: Boolean,
    private val myPriorityRange: ProperTextRange,
    editor: Editor?,
    private val myHighlightInfoProcessor: HighlightInfoProcessor
) : GeneralHighlightingPass(project, file, document, startOffset, endOffset, updateAll, myPriorityRange, editor, myHighlightInfoProcessor) {
    private val myRestrictRange = TextRange(startOffset, endOffset)
    private val myHighlights: MutableList<HighlightInfo> = ArrayList()

    override fun getPresentableName(): String =
        "Macro call bodies"

    private fun RsMacroCall.processMacroCallsRecursively(processor: (RsMacroCall) -> Unit): Unit =
        processMacroCallsRecursively(processor, 0)

    private fun RsMacroCall.processMacroCallsRecursively(processor: (RsMacroCall) -> Unit, depth: Int) {
        if (depth > DEFAULT_RECURSION_LIMIT) return
        expansion?.elements.orEmpty().forEach { it.processRecursively(processor, depth) }
    }

    private fun RsExpandedElement.processRecursively(processor: (RsMacroCall) -> Unit, depth: Int) {
        if (this is RsMacroCall) {
            processor(this)
            processMacroCallsRecursively(processor, depth + 1)
        }
    }

    override fun collectInformationWithProgress(progress: ProgressIndicator) {
        if (myFile !is RsFile) return
        if (myFile.findMacroCallExpandedFrom() != null) return

        val allDivided = mutableListOf<Divider.DividedElements>()
        Divider.divideInsideAndOutsideAllRoots(myFile, myRestrictRange, myPriorityRange, SHOULD_HIGHLIGHT_FILTER, CommonProcessors.CollectProcessor<Divider.DividedElements>(allDivided))

        val allInsideElements = allDivided.map { d -> d.inside }.flatten()
        val allOutsideElements = allDivided.map { d -> d.outside }.flatten()

        val macros0 = (allInsideElements + allOutsideElements).filterIsInstance<RsMacroCall>()
        val macros = macros0 + macros0.flatMap {
            val list = mutableListOf<RsMacroCall>()
            it.processMacroCallsRecursively { list.add(it) }
            list
        }

        val result0 = mutableSetOf<HighlightInfo>()
        addExpansionsHighlights(macros, progress, Collections.synchronizedSet(result0))

        val result: Set<HighlightInfo>
        synchronized(result0) {
            // sync here because all writes happened in another thread
            result = result0
        }

        val gotHighlights = THashSet<HighlightInfo>(100)
        val injectionsOutside = ArrayList<HighlightInfo>(gotHighlights.size)
        for (info in result) {
            if (myRestrictRange.contains(info)) {
                gotHighlights.add(info)
            } else {
                // nonconditionally apply injected results regardless whether they are in myStartOffset,myEndOffset
                injectionsOutside.add(info)
            }
        }

        if (!injectionsOutside.isEmpty()) {
            val priorityIntersection = myPriorityRange.intersection(myRestrictRange)
            if ((!allInsideElements.isEmpty() || !gotHighlights.isEmpty) && priorityIntersection != null) { // do not apply when there were no elements to highlight
                // clear infos found in visible area to avoid applying them twice
                val toApplyInside = ArrayList(gotHighlights)
                myHighlights.addAll(toApplyInside)
                gotHighlights.clear()

                myHighlightInfoProcessor.highlightsInsideVisiblePartAreProduced(getHS(), editor, toApplyInside, myPriorityRange, myRestrictRange,
                    id)
            }

            val toApply = ArrayList<HighlightInfo>()
            for (info in gotHighlights) {
                if (!myRestrictRange.contains(info)) continue
                if (!myPriorityRange.contains(info)) {
                    toApply.add(info)
                }
            }
            toApply.addAll(injectionsOutside)

            myHighlightInfoProcessor.highlightsOutsideVisiblePartAreProduced(getHS(), editor, toApply, myRestrictRange, ProperTextRange(0, myDocument!!.textLength),
                id)
        } else {
            // else apply only result (by default apply command) and only within inside
            myHighlights.addAll(gotHighlights)
            myHighlightInfoProcessor.highlightsInsideVisiblePartAreProduced(getHS(), editor, myHighlights, myRestrictRange, myRestrictRange,
                id)
        }
    }

    private fun getHS(): HighlightingSession {
        return ProgressableTextEditorHighlightingPass::class.java.getDeclaredField("myHighlightingSession").apply {
            isAccessible = true
        }.get(this) as HighlightingSession
    }

    override fun getInfos(): List<HighlightInfo> = myHighlights

    // returns false if canceled
    private fun addExpansionsHighlights(calls: List<RsMacroCall>,
                                        progress: ProgressIndicator,
                                        outInfos: MutableCollection<in HighlightInfo>): Boolean {
        if (calls.isEmpty()) return true
        return JobLauncher.getInstance()
            .invokeConcurrentlyUnderProgress(calls, progress
            ) { call ->
                val expansion = call.expansion ?: return@invokeConcurrentlyUnderProgress true
                addExpansionsHighlights(call, expansion, outInfos)
            }
    }

    private fun addExpansionsHighlights(call: RsMacroCall, expansion: MacroExpansion, outInfos: MutableCollection<in HighlightInfo>): Boolean {
        val holder = createInfoHolder(expansion.file)
        runHighlightVisitorsForExpansion(expansion.file, holder)
        for (i in 0 until holder.size()) {
            val info = holder.get(i)
            ProgressManager.checkCanceled()
            addPatchedInfos(info, call, expansion, outInfos)
        }

        return true
    }

    private fun addPatchedInfos(info: HighlightInfo, call: RsMacroCall, expansion: MacroExpansion, outInfos: MutableCollection<in HighlightInfo>) {
        val startOffset = expansion.file.findElementAt(info.startOffset)
            ?.findElementExpandedFrom()?.startOffset
            ?: return
        val hostRange = TextRange(startOffset, startOffset + info.endOffset - info.startOffset)

        val isAfterEndOfLine = info.isAfterEndOfLine

        val patched = MyHighlightInfo(info.forcedTextAttributes, info.forcedTextAttributesKey, info.type,
            hostRange.startOffset, hostRange.endOffset,
            info.description, info.toolTip, info.type.getSeverity(null), isAfterEndOfLine, null, false, 0, info.problemGroup, info.gutterIconRenderer)
//        patched.setHint(info.hasHint())

        // patched.setFromInjection(true)
        outInfos.add(patched)
    }

    private fun runHighlightVisitorsForExpansion(expansionPsi: PsiFile,
                                                 holder: HighlightInfoHolder) {
        val filtered = getHighlightVisitors(expansionPsi)
        try {
            val elements = CollectHighlightsUtil.getElementsInRange(expansionPsi, 0, expansionPsi.textLength)
            for (visitor in filtered) {
                visitor.analyze(expansionPsi, true, holder) {
                    for (element in elements) {
                        ProgressManager.checkCanceled()
                        visitor.visit(element)
                    }
                }
            }
        } finally {
//            incVisitorUsageCount(-1)
        }
    }

    private fun getHighlightVisitors(psiFile: PsiFile): Array<HighlightVisitor> {
        return filterVisitors(cloneHighlightVisitors(), psiFile)
    }

    private fun cloneHighlightVisitors(): Array<HighlightVisitor> {
//        val oldCount = incVisitorUsageCount(1)
        var highlightVisitors = HighlightVisitor.EP_HIGHLIGHT_VISITOR.getExtensions(myProject)
        @Suppress("ConstantConditionIf")
        if (/*oldCount != 0*/ true) {
            val clones = arrayOfNulls<HighlightVisitor>(highlightVisitors.size)
            for (i in highlightVisitors.indices) {
                val highlightVisitor = highlightVisitors[i]
                val cloned = highlightVisitor.clone()
                assert(cloned.javaClass == highlightVisitor.javaClass) { highlightVisitor.javaClass.toString() + ".clone() must return a copy of " + highlightVisitor.javaClass + "; but got: " + cloned + " of " + cloned.javaClass }
                clones[i] = cloned
            }
            highlightVisitors = clones
        }
        return highlightVisitors
    }

    private fun filterVisitors(highlightVisitors: Array<HighlightVisitor>, psiFile: PsiFile): Array<HighlightVisitor> {
        val visitors = ArrayList<HighlightVisitor>(highlightVisitors.size)
        val list = Arrays.asList(*highlightVisitors)
        for (visitor in DumbService.getInstance(myProject).filterByDumbAwareness(list)) {
            if (visitor is RainbowVisitor && !RainbowHighlighter.isRainbowEnabledWithInheritance(colorsScheme, psiFile.language)) {
                continue
            }
            if (visitor.suitableForFile(psiFile)) {
                visitors.add(visitor)
            }
        }
        if (visitors.isEmpty()) {
//            LOG.error("No visitors registered. list=" +
//                list +
//                "; all visitors are:" +
//                Arrays.asList(*HighlightVisitor.EP_HIGHLIGHT_VISITOR.getExtensions(myProject)))
        }

        return visitors.toTypedArray()
    }

    class MyHighlightInfo(
        forcedTextAttributes: TextAttributes?,
        forcedTextAttributesKey: TextAttributesKey?,
        type: HighlightInfoType,
        startOffset: Int,
        endOffset: Int,
        escapedDescription: String?,
        escapedToolTip: String?,
        severity: HighlightSeverity,
        afterEndOfLine: Boolean,
        needsUpdateOnTyping: Boolean?,
        isFileLevelAnnotation: Boolean,
        navigationShift: Int,
        problemGroup: ProblemGroup?,
        gutterIconRenderer: GutterMark?
    ) : HighlightInfo(
        forcedTextAttributes,
        forcedTextAttributesKey,
        type,
        startOffset,
        endOffset,
        escapedDescription,
        escapedToolTip,
        severity,
        afterEndOfLine,
        needsUpdateOnTyping,
        isFileLevelAnnotation,
        navigationShift,
        problemGroup,
        gutterIconRenderer
    )

    companion object {
        val SHOULD_HIGHLIGHT_FILTER: Condition<PsiFile> = Condition { file ->
            HighlightingLevelManager.getInstance(file.project).shouldHighlight(file)
        }
    }
}

class RsMacroGeneralHighlightingPassFactory(
    val project: Project,
    registrar: TextEditorHighlightingPassRegistrar
) : DirtyScopeTrackingHighlightingPassFactory {
    private val myPassId: Int = registrar.registerTextEditorHighlightingPass(
        this,
        intArrayOf(Pass.UPDATE_ALL),
        null,
        false,
        -1
    )

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        val textRange = FileStatusMap.getDirtyTextRange(editor, myPassId)
            ?: return null
        val visibleRange = VisibleHighlightingPassFactory.calculateVisibleRange(editor)

        return RsMacroGeneralHighlightingPass(
            project,
            file,
            editor.document,
            textRange.startOffset,
            textRange.endOffset,
            true,
            visibleRange,
            editor,
            DefaultHighlightInfoProcessor()
        )
    }

    override fun getPassId(): Int = myPassId
}
