package org.misarch.common.rust

/**
 * Media type as parsed by the Rust `mime` 0.3 crate (used by axum's JSON extractor and multer):
 * type/subtype/suffix lower-cased, the subtype ends at the **last** `+` (suffix) or at `;` (parameters).
 */
data class RustMime(val type: String, val subtype: String, val suffix: String?) {

    /** axum `Json` extractor: `application/json` or `application/(any)+json` (parameters allowed). */
    val isJson: Boolean get() = type == "application" && (subtype == "json" || suffix == "json")

    companion object {
        private fun isToken(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

        /** Parses like `"...".parse::<mime::Mime>()`; null if mime 0.3 rejects the string. */
        fun parse(raw: String?): RustMime? {
            if (raw == null) return null
            if (raw == "*/*") return RustMime("*", "*", null)
            var i = 0
            var slash = -1
            while (i < raw.length) {
                val c = raw[i]
                if (c == '/' && i > 0) {
                    slash = i; i++; break
                }
                if (!isToken(c)) return null
                i++
            }
            if (slash < 0) return null
            val start = i
            var plus = -1
            var end = raw.length
            while (i < raw.length) {
                val c = raw[i]
                if (c == '+' && i > start) {
                    plus = i
                } else if (c == ';' && i > start) {
                    end = i
                    if (!paramsValid(raw, i)) return null
                    break
                } else if (!isToken(c)) {
                    return null
                }
                i++
            }
            val subtypeEnd = if (plus >= 0) plus else end
            return RustMime(
                type = raw.substring(0, slash).lowercase(),
                subtype = raw.substring(slash + 1, subtypeEnd).lowercase(),
                suffix = if (plus >= 0) raw.substring(plus + 1, end).lowercase() else null,
            )
        }

        /** mime 0.3 parameter syntax: `; name=value` with a token or a quoted-string value. */
        private fun paramsValid(s: String, semicolon: Int): Boolean {
            var i = semicolon + 1
            while (i < s.length) {
                while (i < s.length && s[i] == ' ') i++
                if (i >= s.length) return true
                val nameStart = i
                while (i < s.length && isToken(s[i])) i++
                if (i == nameStart || i >= s.length || s[i] != '=') return false
                i++
                if (i < s.length && s[i] == '"') {
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\') i++
                        i++
                    }
                    if (i >= s.length) return false
                    i++
                } else {
                    val valueStart = i
                    while (i < s.length && isToken(s[i])) i++
                    if (i == valueStart) return false
                }
                while (i < s.length && s[i] == ' ') i++
                if (i < s.length) {
                    if (s[i] != ';') return false
                    i++
                }
            }
            return true
        }
    }
}
