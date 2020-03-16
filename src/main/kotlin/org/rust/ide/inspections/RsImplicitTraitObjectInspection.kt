/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.dyn
import org.rust.lang.core.psi.ext.isEdition2018
import org.rust.lang.core.psi.ext.typeElement
import org.rust.lang.core.resolve.ref.deepResolve

class RsImplicitTraitObjectInspection : RsLocalInspectionTool() {
    override fun buildVisitor(holder: RsProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : RsVisitor() {
            override fun visitTypeReference(typeReference: RsTypeReference) {
                if (!typeReference.isEdition2018) return

                val traitType = typeReference.typeElement as? RsTraitType
                val baseTypePath = (typeReference.typeElement as? RsBaseType)?.path
                val isTraitType = traitType != null || baseTypePath?.reference?.deepResolve() is RsTraitItem
                val isSelf = baseTypePath?.cself != null
                val hasDyn = traitType?.dyn != null
                val hasImpl = traitType?.impl != null
                if (!isTraitType || isSelf || hasDyn || hasImpl) return

                holder.registerProblem(
                    typeReference,
                    "Trait objects without an explicit 'dyn' are deprecated",
                    object : LocalQuickFix {
                        override fun getFamilyName(): String = "Add 'dyn' keyword to trait object"

                        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
                            val target = descriptor.psiElement as RsTypeReference
                            val typeElement = target.typeElement
                            val traitText = (typeElement as? RsBaseType)?.path?.text ?: (typeElement as RsTraitType).text
                            val new = RsPsiFactory(project).createDynTraitType(traitText)
                            target.replace(new)
                        }
                    }
                )
            }
        }
}
