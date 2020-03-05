/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.rust.ide.icons.RsIcons
import org.rust.ide.presentation.getPresentation
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.DEFAULT
import org.rust.lang.core.stubs.RsImplItemStub
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.RsPsiTypeImplUtil
import org.rust.lang.core.types.ty.Ty

val RsImplItem.default: PsiElement?
    get() = node.findChildByType(DEFAULT)?.psi

val RsImplItem.isReservationImpl: Boolean
    get() = queryAttributes.hasAttribute("rustc_reservation_impl")

abstract class RsImplItemImplMixin : RsStubbedElementImpl<RsImplItemStub>, RsImplItem {

    constructor(node: ASTNode) : super(node)
    constructor(stub: RsImplItemStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override fun getIcon(flags: Int) = RsIcons.IMPL

    val isPublic: Boolean get() = false // pub does not affect impls at all

    override fun getPresentation(): ItemPresentation = getPresentation(this)

    override val implementedTrait: BoundElement<RsTraitItem>? get() {
        val (trait, subst) = traitRef?.resolveToBoundTrait() ?: return null
        return BoundElement(trait, subst)
    }

    override val innerAttrList: List<RsInnerAttr>
        get() = members?.innerAttrList ?: emptyList()

    override val associatedTypesTransitively: Collection<RsTypeAlias>
        get() = CachedValuesManager.getCachedValue(this) {
            CachedValueProvider.Result.create(
                doGetAssociatedTypesTransitively(),
                rustStructureOrAnyPsiModificationTracker
            )
        }

    private fun doGetAssociatedTypesTransitively(): List<RsTypeAlias> {
        val implAliases = expandedMembers.types
        val traitAliases = implementedTrait?.associatedTypesTransitively ?: emptyList()
        return implAliases + traitAliases.filter { trAl -> implAliases.find { it.name == trAl.name } == null }
    }

    override val declaredType: Ty get() = RsPsiTypeImplUtil.declaredType(this)

    override val isUnsafe: Boolean get() = unsafe != null

    override fun getContext(): PsiElement? = RsExpandedElement.getContextImpl(this)
}
