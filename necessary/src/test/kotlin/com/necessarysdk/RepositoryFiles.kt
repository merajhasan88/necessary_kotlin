package com.necessarysdk

import java.io.File

/** A file of this repository, wherever the tests run from. */
internal fun repositoryFile(path: String): File {
    val root = System.getProperty("necessary.root")?.let(::File) ?: File("..").absoluteFile
    return File(root, path).also { check(it.isFile) { "no such file in the repository: $path" } }
}
