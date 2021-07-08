/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.intellij.codeInsight.template.TemplateResultListener
import com.intellij.codeInsight.template.impl.MacroCallNode
import com.intellij.codeInsight.template.macro.CompleteMacro
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.TestOnly
import org.rust.ide.inspections.fixes.AddTypeArguments
import org.rust.ide.inspections.fixes.insertTypeArgumentsIfNeeded
import org.rust.ide.refactoring.implementMembers.generateMissingTraitMembers
import org.rust.lang.core.psi.RsBaseType
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.ext.*
import org.rust.openapiext.createSmartPointer
import org.rust.openapiext.newTemplateBuilder

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

        if (isUnitTestMode) {
            afterTraitNameEntered(inserted, editor)
        } else {
            val implPtr = inserted.createSmartPointer()
            val traitNamePtr = traitName.createSmartPointer()
            val tpl = editor.newTemplateBuilder(inserted) ?: return
            tpl.replaceElement(traitNamePtr.element ?: return, MacroCallNode(CompleteMacro()))
            tpl.withResultListener {
                if (it == TemplateResultListener.TemplateResult.Finished) {
                    val implCurrent = implPtr.element
                    if (implCurrent != null) {
                        afterTraitNameEntered(implCurrent, editor)
                    }
                }
            }
            tpl.runInline()
        }
    }

    private fun afterTraitNameEntered(impl: RsImplItem, editor: Editor) {
        val traitRef = impl.traitRef ?: return
        val members = impl.members ?: return
        val trait = traitRef.resolveToBoundTrait() ?: return
        val insertedTypeArguments = if (trait.element.requiredGenericParameters.isNotEmpty()) {
            // messy :( nested write actions
            runWriteAction {
                insertTypeArgumentsIfNeeded(traitRef)?.map { it.createSmartPointer() }
            }

        } else {
            null
        }
        generateMissingTraitMembers(impl)
        val restored = insertedTypeArguments?.mapNotNull { it.element }?.filterIsInstance<RsBaseType>()
        if (restored != null && restored.isNotEmpty()) {
            val baseTypes = members.descendantsOfType<RsPath>()
                .filter { it.parent is RsBaseType && !it.hasColonColon && it.path == null && it.typeQual == null }
                .groupBy { it.referenceName }
            val typeToUsage = restored.associateWith {
                it.path?.referenceName?.let { baseTypes[it] } ?: emptyList()
            }
            val tmp = editor.newTemplateBuilder(impl) ?: return
            for ((type, usages) in typeToUsage) {
                tmp.introduceVariable(type).apply {
                    for (usage in usages) {
                        replaceElementWithVariable(usage)
                    }
                }
            }
            tmp.withExpressionsHighlighting()
            tmp.runInline()
        }

//        fixTraitIfNeeded(impl, editor) {
//            generateMissingTraitMembers(impl)
//        }
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
