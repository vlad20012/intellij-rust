/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.ext.RsStructOrEnumItemElement
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.openapiext.buildAndRunTemplate
import org.rust.openapiext.createSmartPointer
import com.intellij.codeInsight.template.TemplateResultListener
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.annotations.TestOnly
import org.rust.ide.inspections.fixes.AddTypeArguments
import org.rust.ide.refactoring.implementMembers.generateMissingTraitMembers
import org.rust.lang.core.psi.ext.requiredGenericParameters
import org.rust.lang.core.psi.ext.resolveToBoundTrait
import org.rust.openapiext.selectElement

class AddImplTraitIntention : RsElementBaseIntentionAction<AddImplTraitIntention.Context>() {
    override fun getText() = "Implement trait"
    override fun getFamilyName() = text

    class Context(val type: RsStructOrEnumItemElement, val name: String)

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        val struct = element.ancestorStrict<RsStructOrEnumItemElement>() ?: return null
        val name = struct.name ?: return null
        return Context(struct, name)
    }

    override fun invoke(project: Project, editor: Editor, ctx: Context) {
        val name = if (isUnitTestMode) {
            traitName!!
        } else {
            "Trait"
        }
        val impl = RsPsiFactory(project).createTraitImplItem(
            ctx.name,
            name,
            ctx.type.typeParameterList,
            ctx.type.whereClause
        )

        val inserted = ctx.type.parent.addAfter(impl, ctx.type) as RsImplItem
        val traitName = inserted.traitRef?.path ?: return

        // Trigger completion
        selectElement(traitName, editor)

        if (isUnitTestMode) {
            afterTraitNameEntered(inserted, editor)
        } else {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)

            val implPtr = inserted.createSmartPointer()
            editor.buildAndRunTemplate(
                inserted,
                listOf(traitName.createSmartPointer()),
                TemplateResultListener {
                    if (it == TemplateResultListener.TemplateResult.Finished) {
                        val implCurrent = implPtr.element
                        if (implCurrent != null) {
                            afterTraitNameEntered(implCurrent, editor)
                        }
                    }
                }
            )
        }
    }

    private fun afterTraitNameEntered(impl: RsImplItem, editor: Editor) {
        fixTraitIfNeeded(impl, editor) {
            generateMissingTraitMembers(impl)
        }
    }

    companion object {
        @TestOnly
        var traitName: String? = null
    }
}

private fun fixTraitIfNeeded(inserted: RsImplItem, editor: Editor, action: () -> Unit) {
    val traitRef = inserted.traitRef ?: return
    val trait = traitRef.resolveToBoundTrait() ?: return
    if (trait.element.requiredGenericParameters.isNotEmpty()) {
        // messy :( nested write actions
        runWriteAction {
            AddTypeArguments.addTypeArguments(traitRef, editor) {
                action()
            }
        }
    } else {
        action()
    }
}
