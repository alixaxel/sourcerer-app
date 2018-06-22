// Copyright 2017 Sourcerer Inc. All Rights Reserved.
// Author: Anatoly Kislov (anatoly@sourcerer.io)
// Author: Liubov Yaronskaya (lyaronskaya@sourcerer.io)

package app.extractors

import app.model.*

interface ExtractorInterface {
    companion object {
        private val classifierManager = ClassifierManager()
        // TODO (anatoly): Download libraries.
        val librariesMeta = LibraryMeta(hashMapOf())

        val stringRegex = Regex("""(".+?"|'.+?')""")
        val splitRegex = Regex("""\s|,|;|\*|\n|\(|\)|\[|]|\{|}|\+|=|&|\$|!=|\.|>|<|#|@|:|\?|!""")
    }

    // Identify libs used in a line with classifiers.
    fun determineLibs(line: String, importedLibs: List<String>): List<String> {
        val lang = getLanguageName()
        if (lang != null) {
            return classifierManager.estimate(tokenize(line), importedLibs)
        }
        return listOf()
    }

    // Should be defined for each language otherwise libs extraction disabled.
    fun extractImports(fileContent: List<String>): List<String> {
        return listOf()
    }

    // Should be defined for additional statistics like keywords.
    fun extract(files: List<DiffFile>): List<CommitStats> {
        val lang = getLanguageName()
        if (lang != null) {
            files.forEach { file -> file.lang = lang }
        }

        return extractLangStats(files) + extractLibStats(files)
    }

    fun extractLangStats(files: List<DiffFile>): List<CommitStats> {
        return files.filter { file -> file.lang.isNotBlank() }
            .groupBy { file -> file.lang }
            .map { (language, files) -> CommitStats(
                numLinesAdded = files.fold(0) { total, file ->
                    total + file.getAllAdded().size },
                numLinesDeleted = files.fold(0) { total, file ->
                    total + file.getAllDeleted().size },
                type = Extractor.TYPE_LANGUAGE,
                tech = language)
            }
    }

    fun extractLibStats(files: List<DiffFile>): List<CommitStats> {
        val oldLibs = extractLibsOfDiffs(files.map { Pair(it.lang, it.old) })
        val newLibs = extractLibsOfDiffs(files.map { Pair(it.lang, it.new) })

        val allLibsIds = oldLibs.keys + newLibs.keys

        return allLibsIds.map { libId -> CommitStats(
            numLinesAdded = newLibs.getOrDefault(libId, 0),
            numLinesDeleted = oldLibs.getOrDefault(libId, 0),
            type = Extractor.TYPE_LIBRARY,
            tech = libId
        ) }.filter { it.numLinesAdded > 0 || it.numLinesDeleted > 0 }
    }

    fun extractLibsOfDiffs(diffs: List<Pair<String, DiffContent>>):
        Map<String, Int> {
        val libsCount = mutableMapOf<String, Int>()

        // Extract imports from files.
        diffs.forEach { (_, diff) ->
            diff.imports = extractImports(diff.content)
        }

        // Skip library stats calculation if no imports found.
        if (!diffs.any({ (_, diff) -> diff.imports.isNotEmpty() })) {
            return mapOf()
        }

        // Determine libraries used in each line.
        diffs.filter { (lang, _) -> lang.isNotBlank() }
            .forEach { (lang, diff) ->
                val importedLibs = diff.imports.mapNotNull { import ->
                    mapImportToIndex(import, lang)
                }

                diff.getAllDiffs().forEach { line ->
                    determineLibs(line, importedLibs).forEach { libId ->
                        libsCount[libId] = libsCount.getOrDefault(libId, 0) + 1
                    }
                }
            }

        return libsCount
    }

    fun tokenize(line: String): List<String> {
        // TODO(lyaronskaya): Multiline comment regex.
        val newLine = stringRegex.replace(line, "")
        val tokens = splitRegex.split(newLine).filter {
            it.isNotBlank() && !it.contains('"') && !it.contains('\'') &&
                it != "-" && it != "@"
        }
        return tokens
    }

    fun getLanguageName(): String? {
        return null
    }

    fun mapImportToIndex(import: String, lang: String): String? {
        return librariesMeta.importToIndexMap[lang]?.get(import)
    }
}
