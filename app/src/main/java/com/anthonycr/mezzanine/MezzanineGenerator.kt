package com.anthonycr.mezzanine

import acr.browser.lightning.BrowserApp
import acr.browser.lightning.html.ListPageReader
import acr.browser.lightning.html.bookmark.BookmarkPageReader
import acr.browser.lightning.html.homepage.HomePageReader
import acr.browser.lightning.js.InvertPage
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.js.ThemeColor

/**
 * Drop-in replacement for the mezzanine kapt-generated MezzanineGenerator.
 * Instead of compile-time code generation, reads files from assets at runtime.
 * Files must be present in src/main/assets/ (InvertPage.js, TextReflow.js,
 * ThemeColor.js, list.html, homepage.html, bookmarks.html).
 */
object MezzanineGenerator {

    private fun readAsset(name: String): String =
        BrowserApp.instance.assets.open(name).bufferedReader().use { it.readText() }

    fun InvertPage(): InvertPage = object : InvertPage {
        override fun provideJs(): String = readAsset("InvertPage.js")
    }

    fun TextReflow(): TextReflow = object : TextReflow {
        override fun provideJs(): String = readAsset("TextReflow.js")
    }

    fun ThemeColor(): ThemeColor = object : ThemeColor {
        override fun provideJs(): String = readAsset("ThemeColor.js")
    }

    fun ListPageReader(): ListPageReader = object : ListPageReader {
        override fun provideHtml(): String = readAsset("list.html")
    }

    fun HomePageReader(): HomePageReader = object : HomePageReader {
        override fun provideHtml(): String = readAsset("homepage.html")
    }

    fun BookmarkPageReader(): BookmarkPageReader = object : BookmarkPageReader {
        override fun provideHtml(): String = readAsset("bookmarks.html")
    }
}
