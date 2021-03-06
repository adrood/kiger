package kiger.temp

private var tempIdSeq = 0;

class Temp constructor(val name: String) : Comparable<Temp> {

    companion object {

        /**
         * Generates a new unique temp.
         */
        fun gen() = Temp("%" + ++tempIdSeq)
    }

    override fun toString() = name
    override fun equals(other: Any?) = other is Temp && name == other.name
    override fun hashCode() = name.hashCode()
    override fun compareTo(other: Temp) = name.compareTo(other.name)
}

/** Useful for making tests deterministic */
fun resetTempSequence() {
    tempIdSeq = 0
}
