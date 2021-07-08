/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.util.LocalTimeCounter
import org.rust.ide.presentation.getStubOnlyText
import org.rust.ide.presentation.renderInsertionSafe
import org.rust.ide.utils.checkMatch.Pattern
import org.rust.lang.RsFileType
import org.rust.lang.RsLanguage
import org.rust.lang.core.macros.MacroExpansionContext
import org.rust.lang.core.macros.prepareExpandedTextForParsing
import org.rust.lang.core.parser.RustParserUtil.PathParsingMode
import org.rust.lang.core.parser.RustParserUtil.PathParsingMode.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.Substitution
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.ty.Mutability
import org.rust.lang.core.types.ty.Mutability.IMMUTABLE
import org.rust.lang.core.types.ty.Mutability.MUTABLE
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.type

class RsPsiFactory(
    private val project: Project,
    private val markGenerated: Boolean = true,
    private val eventSystemEnabled: Boolean = false
) {
    fun createFile(text: CharSequence): RsFile = createPsiFile(text) as RsFile

    /**
     * Returns [PsiPlainTextFile] if [text] is too large.
     * Otherwise returns [RsFile].
     */
    fun createPsiFile(text: CharSequence): PsiFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText(
                "DUMMY.rs",
                RsFileType,
                text,
                /*modificationStamp =*/ LocalTimeCounter.currentTime(), // default value
                /*eventSystemEnabled =*/ eventSystemEnabled, // `false` by default
                /*markAsCopy =*/ markGenerated // `true` by default
            )

    fun createMacroBody(text: String): RsMacroBody? = createFromText(
        "macro_rules! m $text"
    )

    fun createMacroCall(
        context: MacroExpansionContext,
        braces: MacroBraces,
        macroName: String,
        vararg arguments: String
    ): RsMacroCall = createMacroCall(context, braces, macroName, arguments.joinToString(", "))

    fun createMacroCall(
        context: MacroExpansionContext,
        braces: MacroBraces,
        macroName: String,
        argument: String
    ): RsMacroCall {
        val appendSemicolon = (context == MacroExpansionContext.ITEM || context == MacroExpansionContext.STMT) &&
            braces.needsSemicolon
        val semicolon = if (appendSemicolon) ";" else ""
        return createFromText(context.prepareExpandedTextForParsing("$macroName!${braces.wrap(argument)}$semicolon"))
            ?: error("Failed to create macro call")
    }

    fun createSelf(mutable: Boolean = false): RsSelfParameter {
        return createFromText<RsFunction>("fn main(${if (mutable) "mut " else ""}self){}")?.selfParameter
            ?: error("Failed to create self element")
    }

    fun createSelfReference(mutable: Boolean = false): RsSelfParameter {
        return createFromText<RsFunction>("fn main(&${if (mutable) "mut " else ""}self){}")?.selfParameter
            ?: error("Failed to create self element")
    }

    fun createIdentifier(text: String): PsiElement =
        createFromText<RsModDeclItem>("mod ${text.escapeIdentifierIfNeeded()};")?.identifier
            ?: error("Failed to create identifier: `$text`")

    fun createQuoteIdentifier(text: String): PsiElement =
        createFromText<RsLifetimeParameter>("fn foo<$text>(_: &$text u8) {}")?.quoteIdentifier
            ?: error("Failed to create quote identifier: `$text`")

    fun createExpression(text: String): RsExpr =
        tryCreateExpression(text)
            ?: error("Failed to create expression from text: `$text`")

    fun tryCreateExpression(text: CharSequence): RsExpr? =
        createFromText("fn main() { let _ = $text; }")

    fun tryCreateExprStmt(text: CharSequence): RsExprStmt? =
        createFromText("fn main() { $text; }")

    fun createTryExpression(expr: RsExpr): RsTryExpr {
        val newElement = createExpressionOfType<RsTryExpr>("a?")
        newElement.expr.replace(expr)
        return newElement
    }

    fun createIfExpression(condition: RsExpr, thenBranch: RsExpr): RsIfExpr {
        val result = createExpressionOfType<RsIfExpr>("if ${condition.text} { () }")
        val block = result.block!!
        if (thenBranch is RsBlockExpr) {
            block.replace(thenBranch.block)
        } else {
            block.expr!!.replace(thenBranch)
        }
        return result
    }

    fun createIfElseExpression(condition: RsExpr, thenBlock: RsBlock, elseBlock: RsBlock): RsIfExpr {
        val resultIfExpr = createExpressionOfType<RsIfExpr>("if ${condition.text} { () } else { () }")
        resultIfExpr.block!!.replace(thenBlock)
        resultIfExpr.elseBranch!!.block!!.replace(elseBlock)

        return resultIfExpr
    }

    fun createBlockExpr(body: CharSequence): RsBlockExpr =
        createExpressionOfType("{ $body }")

    fun createUnsafeBlockExpr(body: String): RsBlockExpr =
        createExpressionOfType("unsafe { $body }")

    fun createRetExpr(expr: String): RsRetExpr =
        createExpressionOfType("return $expr")

    fun tryCreatePath(text: String, ns: PathParsingMode = TYPE): RsPath? {
        val path = when (ns) {
            TYPE -> createFromText("fn foo(t: $text) {}")
            VALUE -> createFromText<RsPathExpr>("fn main() { $text; }")?.path
            NO_TYPE_ARGS -> error("$NO_TYPE_ARGS mode is not supported; use $TYPE")
        } ?: return null
        if (path.text != text) return null
        return path
    }

    fun createStructLiteral(name: String): RsStructLiteral =
        createExpressionOfType("$name { }")

    fun createStructLiteral(name: String, fields: String): RsStructLiteral =
        createExpressionOfType("$name $fields")

    fun createStructLiteralField(name: String, value: String): RsStructLiteralField {
        return createExpressionOfType<RsStructLiteral>("S { $name: $value }")
            .structLiteralBody
            .structLiteralFieldList[0]
    }

    fun createStructLiteralField(name: String, value: RsExpr? = null): RsStructLiteralField {
        val structLiteralField = createExpressionOfType<RsStructLiteral>("S { $name: () }")
            .structLiteralBody
            .structLiteralFieldList[0]
        if (value != null) structLiteralField.expr?.replace(value)
        return structLiteralField
    }

    data class BlockField(val name: String, val type: Ty, val addPub: Boolean)

    data class TupleField(val type: Ty, val addPub: Boolean)

    fun createBlockFields(fields: List<BlockField>): RsBlockFields {
        val fieldsText = fields.joinToString(separator = ",\n") {
            val typeText = it.type.renderInsertionSafe(includeLifetimeArguments = true)
            "${"pub".iff(it.addPub)}${it.name}: $typeText"
        }
        return createFromText("struct S { $fieldsText }") ?: error("Failed to create block fields")
    }

    fun createTupleFields(fields: List<TupleField>): RsTupleFields {
        val fieldsText = fields.joinToString(separator = ", ") {
            val typeText = it.type.renderInsertionSafe(includeLifetimeArguments = true)
            "${"pub".iff(it.addPub)}$typeText"
        }
        return createFromText("struct S($fieldsText)") ?: error("Failed to create tuple fields")
    }

    @Suppress("unused")
    fun createEnum(text: String): RsEnumItem =
        createFromText(text)
            ?: error("Failed to create enum from text: `$text`")

    fun createStruct(text: String): RsStructItem =
        tryCreateStruct(text)
            ?: error("Failed to create struct from text: `$text`")

    fun tryCreateStruct(text: String): RsStructItem? = createFromText(text)

    fun createStatement(text: String): RsStmt =
        createFromText("fn main() { $text 92; }")
            ?: error("Failed to create statement from text: `$text`")

    fun createLetDeclaration(name: String, expr: RsExpr?, mutable: Boolean = false, type: RsTypeReference? = null): RsLetDecl =
        createStatement("let ${"mut".iff(mutable)}$name${if (type != null) ": ${type.text}" else ""} ${if (expr != null) "= ${expr.text}" else ""};") as RsLetDecl

    fun createType(text: CharSequence): RsTypeReference =
        tryCreateType(text)
            ?: error("Failed to create type from text: `$text`")

    fun tryCreateType(text: CharSequence): RsTypeReference? =
        createFromText("fn main() { let a : $text; }")

    fun createMethodParam(text: String): PsiElement {
        val fnItem: RsFunction = createTraitMethodMember("fn foo($text);")
        return fnItem.selfParameter ?: fnItem.valueParameters.firstOrNull()
        ?: error("Failed to create method param from text: `$text`")
    }

    fun createReferenceType(innerTypeText: String, mutable: Boolean): RsRefLikeType =
        createType("&${if (mutable) "mut " else ""}$innerTypeText").skipParens() as RsRefLikeType

    fun createModDeclItem(modName: String): RsModDeclItem =
        tryCreateModDeclItem(modName) ?: error("Failed to create mod decl with name: `$modName`")

    fun tryCreateModDeclItem(modName: String): RsModDeclItem? =
        createFromText("mod $modName;")

    fun createUseItem(text: String, visibility: String = "", alias: String? = null): RsUseItem {
        val aliasText = if (!alias.isNullOrEmpty()) " as $alias" else ""
        return createFromText("$visibility use $text$aliasText;")
            ?: error("Failed to create use item from text: `$text`")
    }

    fun createUseSpeck(text: String): RsUseSpeck =
        createFromText("use $text;")
            ?: error("Failed to create use speck from text: `$text`")

    fun createExternCrateItem(crateName: String): RsExternCrateItem =
        createFromText("extern crate $crateName;")
            ?: error("Failed to create extern crate item from text: `$crateName`")

    fun createModItem(modName: String, modText: String): RsModItem {
        val text = """
            mod $modName {
                $modText
            }"""
        return createFromText(text) ?: error("Failed to create mod item with name: `$modName` from text: `$modText`")
    }

    fun createTraitMethodMember(text: String): RsFunction {
        return createFromText("trait Foo { $text }") ?: error("Failed to create a method member from text: `$text`")
    }

    fun createMembers(text: String): RsMembers {
        return createFromText("impl T for S {$text}") ?: error("Failed to create members from text: `$text`")
    }

    fun createInherentImplItem(
        name: String,
        typeParameterList: RsTypeParameterList? = null,
        whereClause: RsWhereClause? = null
    ): RsImplItem = createImplTemplate(name, typeParameterList, whereClause)

    fun createTraitImplItem(
        type: String,
        trait: String,
        typeParameterList: RsTypeParameterList? = null,
        whereClause: RsWhereClause? = null
    ): RsImplItem = createImplTemplate("$trait for $type", typeParameterList, whereClause)

    private fun createImplTemplate(
        text: String,
        typeParameterList: RsTypeParameterList?,
        whereClause: RsWhereClause?
    ): RsImplItem {
        val whereText = whereClause?.text ?: ""
        val typeParameterListText = typeParameterList?.text ?: ""
        val typeArgumentListText = if (typeParameterList == null) {
            ""
        } else {
            val parameterNames = typeParameterList.lifetimeParameterList.map { it.quoteIdentifier.text } +
                typeParameterList.typeParameterList.map { it.name } +
                typeParameterList.constParameterList.map { it.name }
            parameterNames.joinToString(", ", "<", ">")
        }

        return createFromText("impl $typeParameterListText $text $typeArgumentListText $whereText {  }")
            ?: error("Failed to create an trait impl with text: `$text`")
    }

    fun createWhereClause(
        lifetimeBounds: List<RsLifetimeParameter>,
        typeBounds: List<RsTypeParameter>
    ): RsWhereClause {

        val lifetimeConstraints = lifetimeBounds
            .filter { it.lifetimeParamBounds != null }
            .mapNotNull { it.text }

        val typeConstraints = typeBounds
            .filter { it.typeParamBounds != null }
            .map {
                //ignore default type parameter
                it.text.take(it.eq?.startOffsetInParent ?: it.textLength)
            }

        val whereClauseConstraints = (lifetimeConstraints + typeConstraints).joinToString(", ")

        val text = "where $whereClauseConstraints"
        return createFromText("fn main() $text {}")
            ?: error("Failed to create a where clause from text: `$text`")
    }

    fun createTypeParameterList(
        params: Iterable<String>
    ): RsTypeParameterList {
        val text = params.joinToString(prefix = "<", separator = ", ", postfix = ">")

        return createFromText<RsFunction>("fn foo$text() {}")?.typeParameterList
            ?: error("Failed to create type from text: `$text`")
    }

    fun createTypeParameterList(
        params: String
    ): RsTypeParameterList {
        return createFromText<RsFunction>("fn foo<$params>() {}")?.typeParameterList
            ?: error("Failed to create type parameters from text: `<$params`>")
    }

    fun createTypeArgumentList(
        params: Iterable<String>
    ): RsTypeArgumentList {
        val text = params.joinToString(prefix = "<", separator = ", ", postfix = ">")
        return createFromText("type T = a$text") ?: error("Failed to create type argument from text: `$text`")
    }

    fun createOuterAttr(text: String): RsOuterAttr =
        createFromText("#[$text] struct Dummy;")
            ?: error("Failed to create an outer attribute from text: `$text`")

    fun createInnerAttr(text: String): RsInnerAttr =
        createFromText("#![$text]")
            ?: error("Failed to create an inner attribute from text: `$text`")

    fun createMatchBody(patterns: List<Pattern>, ctx: RsElement? = null): RsMatchBody {
        val arms = patterns.joinToString("\n") { "${it.text(ctx)} => {}" }
        return createExpressionOfType<RsMatchExpr>("match x { $arms }").matchBody
            ?: error("Failed to create match body from patterns: `$arms`")
    }

    fun createConstant(name: String, expr: RsExpr): RsConstant =
        createFromText("const $name: ${expr.type.renderInsertionSafe(useAliasNames = true, includeLifetimeArguments = true)} = ${expr.text};")
            ?: error("Failed to create constant $name from ${expr.text} ")

    private inline fun <reified T : RsElement> createFromText(code: CharSequence): T? =
        createFile(code).descendantOfTypeStrict()

    fun createPub(): RsVis =
        createFromText("pub fn f() {}")
            ?: error("Failed to create `pub` element")

    fun createPubCrateRestricted(): RsVis =
        createFromText("pub(crate) fn f() {}")
            ?: error("Failed to create `pub(crate)` element")

    fun createBlockComment(text: String): PsiComment =
        PsiParserFacade.SERVICE.getInstance(project).createBlockCommentFromText(RsLanguage, text)

    fun createLineComment(text: String): PsiComment =
        PsiParserFacade.SERVICE.getInstance(project).createLineCommentFromText(RsFileType, text)

    fun createComma(): PsiElement =
        createFromText<RsValueParameter>("fn f(_ : (), )")!!.nextSibling

    fun createSemicolon(): PsiElement =
        createFromText<RsConstant>("const C: () = ();")!!.semicolon!!

    fun createColon(): PsiElement =
        createFromText<RsConstant>("const C: () = ();")!!.colon!!

    fun createEq(): PsiElement =
        createFromText<RsConstant>("const C: () = ();")!!.eq!!

    fun createIn(): PsiElement =
        createFromText<RsConstant>("pub(in self) const C: () = ();")?.vis?.visRestriction?.`in`
            ?: error("Failed to create `in` element")

    fun createNewline(): PsiElement = createWhitespace("\n")

    fun createWhitespace(ws: String): PsiElement =
        PsiParserFacade.SERVICE.getInstance(project).createWhiteSpaceFromText(ws)

    fun createUnsafeKeyword(): PsiElement =
        createFromText<RsFunction>("unsafe fn foo(){}")?.unsafe
            ?: error("Failed to create unsafe element")

    fun createAsyncKeyword(): PsiElement =
        createFromText<RsFunction>("async fn foo(){}")?.node?.findChildByType(RsElementTypes.ASYNC)?.psi
            ?: error("Failed to create async element")

    fun createFunction(text: String): RsFunction =
        tryCreateFunction(text) ?: error("Failed to create function element: $text")

    fun tryCreateFunction(text: String): RsFunction? = createFromText(text)

    fun createRetType(ty: String): RsRetType =
        createFromText("fn foo() -> $ty {}")
            ?: error("Failed to create function return type: $ty")

    fun createImpl(name: String, functions: List<RsFunction>): RsImplItem =
        createFromText("impl $name {\n${functions.joinToString(separator = "\n", transform = { it.text })}\n}")
            ?: error("Failed to create RsImplItem element")

    fun createSimpleValueParameterList(name: String, type: RsTypeReference): RsValueParameterList {
        return createFromText<RsFunction>("fn main($name: ${type.text}){}")
            ?.valueParameterList
            ?: error("Failed to create parameter element")
    }

    fun createValueParameter(
        name: String,
        type: RsTypeReference,
        mutable: Boolean = false,
        reference: Boolean = true,
        lifetime: RsLifetime? = null
    ): RsValueParameter {
        val referenceText = if (reference) "&" else ""
        val lifetimeText = if (lifetime != null) "${lifetime.text} " else ""
        val mutText = if (mutable) "mut " else ""
        return createFromText<RsFunction>("fn main($name: $referenceText$lifetimeText$mutText${type.text}){}")
            ?.valueParameterList?.valueParameterList?.get(0)
            ?: error("Failed to create parameter element")
    }

    fun createPatFieldFull(name: String, value: String): RsPatFieldFull =
        createFromText("fn f(A{$name: $value}: ()) {}")
            ?: error("Failed to create full field pattern")

    fun createPatBinding(name: String, mutable: Boolean = false, ref: Boolean = false): RsPatBinding =
        (createStatement("let ${"ref ".iff(ref)}${"mut ".iff(mutable)}$name = 10;") as RsLetDecl).pat
            ?.firstChild as RsPatBinding?
            ?: error("Failed to create pat element")

    fun createPatField(name: String): RsPatField =
        createFromText("""
            struct Foo { bar: i32 }
            fn baz(foo: Foo) {
                let Foo { $name } = foo;
            }
        """) ?: error("Failed to create pat field")

    fun createPatStruct(name: String, pats: List<RsPatField>, patRest: RsPatRest?): RsPatStruct {
        val pad = if (pats.isEmpty()) "" else " "
        val items = pats.map { it.text } + listOfNotNull(patRest?.text)
        val body = items
            .joinToString(separator = ", ", prefix = " {$pad", postfix = "$pad}")
        return createFromText("fn f($name$body: $name) {}") ?: error("Failed to create pat struct")
    }

    fun createPatStruct(struct: RsStructItem, name: String? = null): RsPatStruct {
        val structName = name ?: struct.name ?: error("Failed to create pat struct")
        val pad = if (struct.namedFields.isEmpty()) "" else " "
        val body = struct.namedFields
            .joinToString(separator = ", ", prefix = " {$pad", postfix = "$pad}") { it.name ?: "_" }
        return createFromText("fn f($structName$body: $structName) {}") ?: error("Failed to create pat struct")
    }

    fun createPatTupleStruct(struct: RsStructItem, name: String? = null): RsPatTupleStruct {
        val structName = name ?: struct.name ?: error("Failed to create pat tuple struct")
        val body = struct.positionalFields
            .joinToString(separator = ", ", prefix = "(", postfix = ")") { "_${it.name}" }
        return createFromText("fn f($structName$body: $structName) {}") ?: error("Failed to create pat tuple struct")
    }

    fun createPatTupleStruct(name: String, pats: List<RsPat>): RsPatTupleStruct {
        val patsText = pats.joinToString(", ", prefix = "(", postfix = ")") { it.text }
        return createFromText("fn f($name${patsText}: $name) {}") ?: error("Failed to create pat tuple struct")
    }

    fun createPatTuple(fieldNum: Int): RsPatTup {
        val tuple = (0 until fieldNum).joinToString(separator = ", ", prefix = "(", postfix = ")") { "_$it" }
        return createFromText("fn f() { let $tuple = x; }") ?: error("Failed to create pat tuple")
    }

    fun createPatRest(): RsPatRest {
        return createFromText("fn f(S {..}: S) {}") ?: error("Failed to create pat rest")
    }

    fun createCastExpr(expr: RsExpr, typeText: String): RsCastExpr = when (expr) {
        is RsBinaryExpr -> createExpressionOfType("(${expr.text}) as $typeText")
        else -> createExpressionOfType("${expr.text} as $typeText")
    }

    fun createFunctionCall(functionName: String, arguments: Iterable<RsExpr>): RsCallExpr =
        createExpressionOfType("$functionName(${arguments.joinToString { it.text }})")

    fun createFunctionCall(functionName: String, arguments: String): RsCallExpr =
        createExpressionOfType("$functionName(${arguments})")

    fun createAssocFunctionCall(typeText: String, methodNameText: String, arguments: Iterable<RsExpr>): RsCallExpr {
        val isCorrectTypePath = tryCreatePath(typeText) != null
        val typePath = if (isCorrectTypePath) typeText else "<$typeText>"
        return createExpressionOfType("$typePath::$methodNameText(${arguments.joinToString { it.text }})")
    }

    fun createNoArgsMethodCall(expr: RsExpr, methodNameText: String): RsDotExpr = when (expr) {
        is RsBinaryExpr, is RsUnaryExpr, is RsCastExpr -> createExpressionOfType("(${expr.text}).$methodNameText()")
        else -> createExpressionOfType("${expr.text}.$methodNameText()")
    }

    fun tryCreateMethodCall(receiver: RsExpr, methodNameText: String, arguments: List<RsExpr>): RsDotExpr? =
        tryCreateExpressionOfType("${receiver.text}.$methodNameText(${arguments.joinToString(", ") { it.text }})")

    fun tryCreateValueArgumentList(arguments: List<RsExpr>): RsValueArgumentList? =
        createFromText("fn bar() { foo(${arguments.joinToString(", ") { it.text }}); }")

    fun createDerefExpr(expr: RsExpr, nOfDerefs: Int = 1): RsExpr =
        if (nOfDerefs > 0)
            when (expr) {
                is RsBinaryExpr, is RsCastExpr -> createExpressionOfType("${"*".repeat(nOfDerefs)}(${expr.text})")
                else -> createExpressionOfType("${"*".repeat(nOfDerefs)}${expr.text}")
            }
        else expr

    fun createRefExpr(expr: RsExpr, muts: List<Mutability> = listOf(IMMUTABLE)): RsExpr =
        if (!muts.none())
            when (expr) {
                is RsBinaryExpr, is RsCastExpr -> createExpressionOfType("${mutsToRefs(muts)}(${expr.text})")
                else -> createExpressionOfType("${mutsToRefs(muts)}${expr.text}")
            }
        else expr

    fun createVisRestriction(pathText: String): RsVisRestriction {
        val inPrefix = when (pathText) {
            "crate", "super", "self" -> ""
            else -> "in "
        }
        return createFromText<RsFunction>("pub($inPrefix$pathText) fn foo() {}")?.vis?.visRestriction
            ?: error("Failed to create vis restriction element")
    }

    fun tryCreateVis(text: String): RsVis? = createFromText("$text fn foo() {}")

    fun createVis(text: String): RsVis = tryCreateVis(text) ?: error("Failed to create vis")

    private inline fun <reified E : RsExpr> createExpressionOfType(text: String): E =
        createExpression(text) as? E
            ?: error("Failed to create ${E::class.simpleName} from `$text`")

    private inline fun <reified E : RsExpr> tryCreateExpressionOfType(text: String): E? = createExpression(text) as? E

    fun createDynTraitType(pathText: String): RsTraitType =
        createFromText("type T = &dyn $pathText;}")
            ?: error("Failed to create trait type")

    fun createPat(patText: String): RsPat = tryCreatePat(patText) ?: error("Failed to create pat element")

    fun tryCreatePat(patText: String): RsPat? = (createStatement("let $patText;") as RsLetDecl).pat
}

private fun String.iff(cond: Boolean) = if (cond) "$this " else " "

fun RsTypeReference.substAndGetText(subst: Substitution): String =
    getStubOnlyText(subst)

fun Ty.substAndGetText(subst: Substitution): String =
    substitute(subst).renderInsertionSafe(
        includeLifetimeArguments = true,
        useAliasNames = true,
        skipUnchangedDefaultTypeArguments = true
    )

private fun mutsToRefs(mutability: List<Mutability>): String =
    mutability.joinToString("", "", "") {
        when (it) {
            IMMUTABLE -> "&"
            MUTABLE -> "&mut "
        }
    }
