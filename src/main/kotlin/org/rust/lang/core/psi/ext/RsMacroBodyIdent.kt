/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.rust.lang.core.macros.findExpansionElements
import org.rust.lang.core.psi.RsMacroBodyIdent
import org.rust.lang.core.resolve.ref.RsReference
import org.rust.lang.core.resolve.ref.RsReferenceBase

abstract class RsMacroBodyIdentMixin(node: ASTNode) : RsElementImpl(node), RsMacroBodyIdent {
    override fun getReference(): RsReference? = RsMacroBodyIdentReferenceImpl(this)

    override val referenceNameElement: PsiElement
        get() = identifier
}

private class RsMacroBodyIdentReferenceImpl(
    element: RsMacroBodyIdent
) : RsReferenceBase<RsMacroBodyIdent>(element) {
    override val RsMacroBodyIdent.referenceAnchor: PsiElement?
        get() = element.referenceNameElement

    private val delegates: List<RsReference>
        get() {
            return element.findExpansionElements()?.mapNotNull { delegated ->
                val reference = delegated.ancestors
                    .mapNotNull { it.reference }
                    .firstOrNull() ?: return@mapNotNull null

                val rangeInElement = TextRange.from(
                    delegated.startOffset - reference.element.textRange.startOffset,
                    delegated.textLength
                )
                if (rangeInElement != reference.rangeInElement) return@mapNotNull null
                reference as? RsReference
            } ?: emptyList()
        }

    override fun multiResolve(): List<RsElement> =
        delegates.flatMap { it.multiResolve().toList() }.distinct()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return delegates.flatMap { it.multiResolve(incompleteCode).toList() }.distinct().toTypedArray()
    }
}
