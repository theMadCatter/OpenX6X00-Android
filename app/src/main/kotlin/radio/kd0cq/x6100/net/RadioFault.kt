// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before: the one status string was lastAnswer. A panel "err view only" or
// "err busy" landed there, and the next TCI frequency report replaced it with
// "TCI at N Hz". The radio face, FT8 and JS8 never read that string, so the
// refusal disappeared and the operator saw a gold Take control that did nothing.
//
// This is the decision those screens share. A failure replaces what is shown.
// A TCI frequency report does not. Taking control clears it, because that is
// the answer to those two refusals. Any other word leaves the failure up.

package radio.kd0cq.x6100.net

object RadioFault {
    enum class Kind {
        /** A panel "err ..." line. The text is what the face should say. */
        FAILURE,

        /** "TCI at N Hz". The dial already shows the frequency. */
        TCI_FREQUENCY,

        /** This phone now holds the controls. The refusal it was reading is done. */
        CONTROL_MINE,

        /** A notice, a frequency that is not ours, or someone else holding control. */
        OTHER,
    }

    /**
     * The next fault to show.
     *
     * [text] is used only for [Kind.FAILURE]. The other kinds either keep
     * [current] or clear it. A TCI frequency must not become the shown line.
     */
    fun keep(current: String?, kind: Kind, text: String = ""): String? = when (kind) {
        Kind.FAILURE -> present(text)
        Kind.TCI_FREQUENCY -> current
        Kind.CONTROL_MINE -> null
        Kind.OTHER -> current
    }

    /**
     * The radio's parser drops the leading "err ". Put it back so the face
     * says "err view only" and "err busy", which is the line the operator
     * would read on the radio.
     */
    fun present(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "err"
        return if (trimmed.startsWith("err")) trimmed else "err $trimmed"
    }

    /**
     * The line under the controls. Blank pieces are dropped. The same sentence
     * is not repeated when the gate and the local refusal say the same thing.
     * [separator] is a newline on the radio face, where there is room to wrap,
     * and a middle dot on FT8 and JS8, where the header has one line.
     */
    fun show(vararg parts: String?, separator: String = "\n"): String? {
        val kept = parts.asSequence()
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (kept.isEmpty()) return null
        return kept.joinToString(separator)
    }
}
