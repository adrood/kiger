package kiger.target

import kiger.assem.Instr
import kiger.ir.tree.TreeStm

interface CodeGen {
    val frameType: FrameType
    fun codeGen(frame: Frame, stm: TreeStm): List<Instr>
}
