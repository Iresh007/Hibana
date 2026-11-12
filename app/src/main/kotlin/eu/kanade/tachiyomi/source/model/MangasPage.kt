// Copied unmodified from mihonapp/mihon v0.21.3 (Apache License 2.0, https://github.com/mihonapp/mihon).
package eu.kanade.tachiyomi.source.model

class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean) {

    @Deprecated("MangasPage is now a regular class")
    operator fun component1(): List<SManga> = mangas

    @Deprecated("MangasPage is now a regular class")
    operator fun component2(): Boolean = hasNextPage

    @Deprecated("MangasPage is now a regular class")
    fun copy(
        mangas: List<SManga> = this.mangas,
        hasNextPage: Boolean = this.hasNextPage,
    ): MangasPage = MangasPage(
        mangas = mangas,
        hasNextPage = hasNextPage,
    )
}
