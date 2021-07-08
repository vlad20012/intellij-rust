/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.openapiext

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.template.*
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.VariableNode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import org.rust.lang.core.psi.ext.startOffset

fun Editor.buildAndRunTemplate(
    owner: PsiElement,
    elementsToReplace: List<SmartPsiElementPointer<out PsiElement>>,
    listener: TemplateEditingListener? = null,
) {
    checkWriteAccessAllowed()
    val templateBuilder = newTemplateBuilder(owner) ?: return
    for (elementPointer in elementsToReplace) {
        val element = elementPointer.element ?: continue
        templateBuilder.replaceElement(element, element.text)
    }
    if (listener != null) {
        templateBuilder.withListener(listener)
    }
    templateBuilder.runInline()
}

fun Editor.newTemplateBuilder(owner: PsiElement): RsTemplateBuilder? {
    val restoredOwner = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(owner) ?: return null
    val templateBuilder = TemplateBuilderFactory.getInstance().createTemplateBuilder(restoredOwner)
        as TemplateBuilderImpl
    val rootEditor = InjectedLanguageEditorUtil.getTopLevelEditor(this)
    return RsTemplateBuilder(restoredOwner, templateBuilder, this, rootEditor)
}

/**
 * A wrapper for [TemplateBuilder][com.intellij.codeInsight.template.TemplateBuilder]
 */
@Suppress("UnstableApiUsage", "unused")
class RsTemplateBuilder(
    private val owner: PsiElement,
    private val delegate: TemplateBuilderImpl,
    private val editor: Editor,
    private val hostEditor: Editor,
) {
    private val variables: MutableMap<String, TemplateVariable> = mutableMapOf()
    private var variableCounter: Int = 0
    private var highlightExpressions: Boolean = false
    private val listeners: MutableList<TemplateEditingListener> = mutableListOf()

    fun replaceElement(element: PsiElement, replacementText: @NlsSafe String): RsTemplateBuilder {
        delegate.replaceElement(element, replacementText)
        return this
    }

    fun replaceElement(element: PsiElement, rangeWithinElement: TextRange, replacementText: String): RsTemplateBuilder {
        delegate.replaceElement(element, rangeWithinElement, replacementText)
        return this
    }


    fun replaceElement(element: PsiElement, expression: Expression?): RsTemplateBuilder {
        delegate.replaceElement(element, expression)
        return this
    }

    fun replaceElement(element: PsiElement, rangeWithinElement: TextRange, expression: Expression?): RsTemplateBuilder {
        delegate.replaceElement(element, rangeWithinElement, expression)
        return this
    }


    private fun replaceElement(element: PsiElement, variable: TemplateVariable, replacementText: String, alwaysStopAt: Boolean): RsTemplateBuilder {
        delegate.replaceElement(element, variable.name, ConstantNode(replacementText), alwaysStopAt)
        return this
    }

    fun replaceElementWithVariable(element: PsiElement, variable: TemplateVariable): RsTemplateBuilder {
        delegate.replaceElement(element, VariableNode(variable.name, null), false)
        return this
    }


    fun replaceRange(rangeWithinElement: TextRange, replacementText: String): RsTemplateBuilder {
        delegate.replaceRange(rangeWithinElement, replacementText)
        return this
    }

    fun replaceRange(rangeWithinElement: TextRange, expression: Expression?): RsTemplateBuilder {
        delegate.replaceRange(rangeWithinElement, expression)
        return this
    }

    fun introduceVariable(element: PsiElement, replacementText: String? = null): TemplateVariable {
        val variable = newVariable()
        replaceElement(element, variable, replacementText ?: element.text, true)
        return variable
    }

    fun introduceVariableAnd(element: PsiElement, replacementText: String? = null, action: TemplateVariable.() -> Unit): RsTemplateBuilder {
        val variable = introduceVariable(element, replacementText)
        action(variable)
        return this
    }

    fun newVariable(): TemplateVariable {
        var name: String
        do {
            variableCounter++
            name = "variable$variableCounter"
        } while (variables[name] != null)
        return newVariable(name)
    }

    fun newVariable(name: String): TemplateVariable {
        if (variables[name] != null) error("The variable `$variables` is already defined")
        val variable = TemplateVariable(this, name)
        variables[name] = variable
        return variable
    }

    private fun checkVariable(variable: TemplateVariable) {
        check(variable.builder == this && variables[variable.name] == variable)
    }

    fun withExpressionsHighlighting(): RsTemplateBuilder {
        highlightExpressions = true
        return this
    }

    fun withListener(listener: TemplateEditingListener) {
        listeners += listener
    }

    fun withResultListener(listener: (TemplateResultListener.TemplateResult) -> Unit) {
        listeners += TemplateResultListener(listener)
    }

    fun runInline() {
        // From TemplateBuilderImpl.run()
        val template = delegate.buildInlineTemplate()
        editor.caretModel.moveToOffset(owner.startOffset)
        val templateState = TemplateManager.getInstance(owner.project).runTemplate(hostEditor, template)

        val isAlreadyFinished = templateState.isFinished // Can be true in unit tests
        for (listener in listeners) {
            if (isAlreadyFinished) {
                listener.templateFinished(templateState.template, false)
            } else {
                templateState.addTemplateStateListener(listener)
            }
        }

        if (highlightExpressions && !isAlreadyFinished) {
            val highlightManager: HighlightManager = HighlightManager.getInstance(owner.project)
            val highlighters = ArrayList<RangeHighlighter>()
            val key = EditorColors.SEARCH_RESULT_ATTRIBUTES
            for (i in 0 until templateState.segmentsCount) {
                val range = templateState.getSegmentRange(i)
                val variableName = template.getSegmentName(i)
                if (variableName !in variables) {
                    highlightManager.addOccurrenceHighlight(hostEditor, range.startOffset, range.endOffset, key, 0, highlighters)
                }
            }
            if (highlighters.isNotEmpty()) {
                Disposer.register(templateState) {
                    for (highlighter: RangeHighlighter in highlighters) {
                        highlightManager.removeSegmentHighlighter(hostEditor, highlighter)
                    }
                }
            }
        }
    }

    class TemplateVariable(val builder: RsTemplateBuilder, val name: String) {
        fun replaceElementWithVariable(element: PsiElement) {
            builder.replaceElementWithVariable(element, this)
        }
    }
}
