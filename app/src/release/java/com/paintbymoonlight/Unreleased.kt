package com.paintbymoonlight

/** Release builds leave out what iOS keeps behind `#if UNRELEASED` (the debug build's copy says what). */
object Unreleased {
    const val ON = false
}
