/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.RsMtt
import org.rust.lang.core.resolve.ref.RsReferenceBase

abstract class RsMttMixin(node: ASTNode) : RsElementImpl(node), RsMtt {
    override fun getReference(): PsiReference? {
        val call = ancestorStrict<RsMacroCall>() ?: return null
        val expansion = call.expansion ?: return null

        val macroOffset = call.macroArgument?.compactTT?.startOffset ?: return null
        val elementOffset = this.startOffset - macroOffset
        check(elementOffset >= 0)
        val mappedRanges = expansion.ranges.map(TextRange(elementOffset, elementOffset + this.textLength))
        val references = mappedRanges.mapNotNull { mappedRange ->
            val delegated = expansion.file.findElementAt(mappedRange.startOffset)
                ?.takeIf { it.textRange == mappedRange }
                ?: return@mapNotNull null

            val reference = delegated.ancestors
                .mapNotNull { it.reference }
                .firstOrNull() ?: return@mapNotNull null

            val rangeInElement = TextRange.from(mappedRange.startOffset - reference.element.textRange.startOffset, mappedRange.length)
            if (rangeInElement != reference.rangeInElement) return@mapNotNull null
            reference as? PsiPolyVariantReferenceBase<*>
        }

        if (references.isEmpty()) return null

        return MultiDelegatedPsiReference(this, references)
    }
}

private class MultiDelegatedPsiReference(psiElement: RsMtt, val delegates: List<PsiPolyVariantReferenceBase<*>>) : PsiPolyVariantReferenceBase<RsMtt>(psiElement) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return delegates.flatMap { it.multiResolve(incompleteCode).toList() }.distinct().toTypedArray()
    }

    override fun getRangeInElement(): TextRange {
        return TextRange(0, element.textLength)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        RsReferenceBase.doRename(element.firstChild, newElementName)
        return element
    }
}

private class DelegatedPsiReference(psiElement: RsMtt, val delegate: PsiPolyVariantReferenceBase<*>) : PsiPolyVariantReferenceBase<RsMtt>(psiElement) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return delegate.multiResolve(incompleteCode)
    }

    override fun getRangeInElement(): TextRange {
        return TextRange(0, element.textLength)
    }
}
