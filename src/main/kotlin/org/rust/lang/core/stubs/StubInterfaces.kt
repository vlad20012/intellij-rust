/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.stubs

import com.intellij.util.BitUtil
import org.rust.lang.core.psi.ext.QueryAttributes
import org.rust.lang.core.psi.ext.RsDocAndAttributeOwner
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.queryAttributes
import org.rust.lang.doc.hasDocumentation
import org.rust.stdext.makeBitMask

interface RsNamedStub {
    val name: String?
}

interface RsDocAndAttributeOwnerStub {
    val hasDocs: Boolean
    val hasAttrs: Boolean
    // #[cfg()]
    val hasCfg: Boolean
    // #[cfg_attr()]
    val hasCfgAttr: Boolean

    companion object {
        val DOCS_MASK: Int = makeBitMask(0)
        val ATTRS_MASK: Int = makeBitMask(1)
        val CFG_MASK: Int = makeBitMask(2)
        val CFG_ATTR_MASK: Int = makeBitMask(3)
        const val USED_BITS: Int = 4

        fun prepareData(element: RsDocAndAttributeOwner, attrs: QueryAttributes = element.queryAttributes): Int {
            val hasDocs = element.hasDocumentation()
            var hasAttrs = false
            var hasCfg = false
            var hasCfgAttr = false
            for (meta in attrs.metaItems) {
                hasAttrs = true
                if (meta.name == "cfg") {
                    hasCfg = true
                }
                if (meta.name == "cfg_attr") {
                    hasCfgAttr = true
                }
                if (hasCfg && hasCfgAttr) break
            }
            var flags = 0
            flags = BitUtil.set(flags, DOCS_MASK, hasDocs)
            flags = BitUtil.set(flags, ATTRS_MASK, hasAttrs)
            flags = BitUtil.set(flags, CFG_MASK, hasCfg)
            flags = BitUtil.set(flags, CFG_ATTR_MASK, hasCfgAttr)
            return flags
        }
    }
}
