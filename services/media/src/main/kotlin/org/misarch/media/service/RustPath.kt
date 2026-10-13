package org.misarch.media.service

/** Rust `std::path::Path::file_stem` for object keys (unix paths). */
object RustPath {
    /** null where Rust returns `None` (no file name: empty key, `..`). */
    fun fileStem(key: String): String? {
        val name = key.split('/').filter { it.isNotEmpty() && it != "." }.lastOrNull() ?: return null
        if (name == "..") return null
        if (name.startsWith(".") && name.indexOf('.', 1) < 0) return name
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}
