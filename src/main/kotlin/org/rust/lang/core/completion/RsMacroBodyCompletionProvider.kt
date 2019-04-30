/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.util.Key
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import org.rust.lang.RsLanguage
import org.rust.lang.core.macros.findExpansionElements
import org.rust.lang.core.psi.RsElementTypes.MACRO_ARGUMENT
import org.rust.lang.core.psi.ext.startOffset

object RsMacroBodyCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        val dstElement = position.findExpansionElements()?.firstOrNull() ?: return
        if (RsCommonCompletionProvider.elementPattern.accepts(dstElement)) {
            val dstOffset = dstElement.startOffset + (parameters.offset - position.startOffset)
            val dstParameters = parameters.withPosition(dstElement, dstOffset)
            context.put(SOURCE_COMPLETION_PARAMETERS, parameters)
            RsCommonCompletionProvider.addCompletionVariants(dstParameters, context, result)
        }
    }

    val elementPattern: ElementPattern<PsiElement>
        get() {
            return psiElement()
                .withLanguage(RsLanguage)
                .inside(psiElement(MACRO_ARGUMENT))
        }
}

private val SOURCE_COMPLETION_PARAMETERS: Key<CompletionParameters> = Key.create("SOURCE_COMPLETION_PARAMETERS")

val ProcessingContext.sourceCompletionParameters: CompletionParameters?
    get() = get(SOURCE_COMPLETION_PARAMETERS)
