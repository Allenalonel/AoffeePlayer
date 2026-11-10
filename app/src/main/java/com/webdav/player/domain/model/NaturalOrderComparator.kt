package com.webdav.player.domain.model

/**
 * Natural order comparator for alphanumeric strings.
 * Ensures natural numerical order (e.g. "Track 2" < "Track 10")
 * while being case-insensitive and handling multi-digit numbers gracefully.
 */
object NaturalOrderComparator : Comparator<String?> {
    override fun compare(s1: String?, s2: String?): Int {
        if (s1 === s2) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        var i1 = 0
        var i2 = 0
        val len1 = s1.length
        val len2 = s2.length

        while (i1 < len1 && i2 < len2) {
            val c1 = s1[i1]
            val c2 = s2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                val start1 = i1
                while (i1 < len1 && s1[i1].isDigit()) i1++
                val numStr1 = s1.substring(start1, i1)

                val start2 = i2
                while (i2 < len2 && s2[i2].isDigit()) i2++
                val numStr2 = s2.substring(start2, i2)

                val numComp = compareNumericStrings(numStr1, numStr2)
                if (numComp != 0) return numComp
            } else {
                val comp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (comp != 0) return comp
                i1++
                i2++
            }
        }

        val lenComp = (len1 - i1).compareTo(len2 - i2)
        if (lenComp != 0) return lenComp

        return s1.compareTo(s2)
    }

    private fun compareNumericStrings(s1: String, s2: String): Int {
        var start1 = 0
        while (start1 < s1.length && s1[start1] == '0') start1++
        var start2 = 0
        while (start2 < s2.length && s2[start2] == '0') start2++

        val sigLen1 = s1.length - start1
        val sigLen2 = s2.length - start2

        if (sigLen1 != sigLen2) {
            return sigLen1.compareTo(sigLen2)
        }

        for (i in 0 until sigLen1) {
            val d1 = s1[start1 + i]
            val d2 = s2[start2 + i]
            if (d1 != d2) {
                return d1.compareTo(d2)
            }
        }

        return s1.length.compareTo(s2.length)
    }
}
