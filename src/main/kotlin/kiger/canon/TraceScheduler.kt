package kiger.canon

import kiger.temp.Label
import kiger.tree.TreeExp.Name
import kiger.tree.TreeStm
import kiger.tree.TreeStm.Branch.CJump
import kiger.tree.TreeStm.Branch.Jump
import kiger.tree.TreeStm.Labeled
import java.util.*

/**
 * Builds a trace from [BasicBlockGraph].
 *
 * The trace satisfies the following conditions:
 *
 *   1. No SEQ's or ESEQ's (guarantee from [linearize])
 *   2. The parent of every CALL is an EXP(..) or a MOVE(TEMP t,..) (guarantee from [linearize])
 *   3. Every CJUMP(_,t,f) is immediately followed by LABEL f.
 *
 * Basic blocks are reordered as necessary to satisfy property 3.
 *
 * In addition to this, the scheduler also tries to eliminate `Jump(Name(lab))` statements by
 * trying to write the target immediately after the jump, so that the we can simply fall through.
 */
fun BasicBlockGraph.traceSchedule(): List<TreeStm> {
    val scheduler = TraceScheduler(blocks)
    scheduler.buildTrace()
    return scheduler.output + TreeStm.Labeled(exitLabel)
}

private class TraceScheduler(blocks: List<BasicBlock>) {

    /**
     * All of the blocks to process.
     *
     * Whenever there's no better candidate to choose, blocks will be traced from the front of
     * this queue. However, tracing might want to trace other blocks immediately, in which case
     * they will still be present in the queue. Whenever [buildTrace] takes blocks from the
     * queue, it will therefore check [untracedBlocks] if they are actually still untraced.
     */
    private val workQueue = ArrayDeque(blocks)

    /**
     * Mapping from labels to all untraced blocks. Initially will contain all blocks to schedule.
     */
    private val untracedBlocks = mutableMapOf<Label, BasicBlock>().apply {
        for (b in blocks)
            this[b.label] = b
    }

    /** The result of the processing */
    val output = mutableListOf<TreeStm>()

    /**
     * Processes all blocks from the work-queue to build a trace.
     */
    fun buildTrace() {
        while (workQueue.any()) {
            val block = workQueue.removeFirst()

            if (block.label in untracedBlocks)
                trace(block)
        }
    }

    /**
     * Traces one or more blocks. Will always write the trace of [block] to output,
     * but also tries to trace the following blocks near this block unless they have
     * already been traced.
     */
    private tailrec fun trace(block: BasicBlock) {
        val b = untracedBlocks.remove(block.label)
        check(b != null) { "attempted to re-trace a block: ${block.label}" }

        val br = block.branch
        when (br) {
            is Jump  -> {
                if (br.exp is Name) {
                    // If we see an unconditional jump at the end of block, try to write
                    // the target immediately after this block and eliminate the jump.
                    // If the target has already been traced, then proceed normally.
                    val targetBlock = untracedBlocks[br.exp.label]
                    if (targetBlock != null) {
                        output += block.labelledBody
                        trace(targetBlock)
                    } else {
                        output += block.allStatements
                    }

                } else {
                    output += block.allStatements
                }
            }
            is CJump -> {
                val trueTarget = untracedBlocks[br.trueLabel]
                val falseTarget = untracedBlocks[br.trueLabel]

                // Try to get the false-block immediately after condition. If that fails, negate
                // the condition and try to get the true block immediately after the condition.
                // If both true and false blocks have already been traced, write a new false-branch
                // immediately after condition that just jumps to the real false-block.
                when {
                    falseTarget != null -> {
                        output += block.allStatements
                        trace(falseTarget)
                    }
                    trueTarget != null -> {
                        output += block.labelledBody
                        output += CJump(br.relop.not(), br.lhs, br.rhs, br.falseLabel, br.trueLabel)
                        trace(trueTarget)
                    }
                    else -> {
                        val f = Label()
                        output += block.labelledBody
                        output += CJump(br.relop, br.lhs, br.rhs, br.trueLabel, f)
                        output += Labeled(f)
                        output += Jump(br.falseLabel)
                    }
                }
            }
        }
    }
}