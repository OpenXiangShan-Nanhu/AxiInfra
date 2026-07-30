// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package generator

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, FirtoolOption}
import freechips.rocketchip.util.AsyncQueueParams
import xs.infra.axi._

object Generator {
  val firtoolOpts = Seq(
    FirtoolOption("-O=release"),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--strip-debug-info"),
    //    FirtoolOption("--lower-memories"),
    FirtoolOption("--disable-all-randomization"),
    FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
    FirtoolOption("--lowering-options=noAlwaysComb," +
      " disallowPortDeclSharing, disallowLocalVariables," +
      " emittedLineLength=120, explicitBitcast," +
      " locationInfoStyle=plain, disallowMuxInlining")
  )

  def disableVerificationLayers(): Unit = {
    chisel3.VerificationLayers.assertLayer = false
    chisel3.VerificationLayers.coverLayer = false
    chisel3.VerificationLayers.assumeLayer = false
  }

  def emit(args: Array[String], gen: () => chisel3.RawModule): Unit = {
    disableVerificationLayers()
    (new ChiselStage).execute(args, firtoolOpts ++ Seq(ChiselGeneratorAnnotation(gen)))
  }
}

/** Full LMSS subsystem top (AxiSubsysTop + SubsysConfig). */
object LmssTop extends App {
  val config = new SubsysConfig
  Generator.emit(args, () => new AxiSubsysTop()(config))
}

/** AxiBridgeCfg (CFG bridge, 1S x 3M). */
object AxiBridgeCfgTop extends App {
  val config = new AxiBridgeCfgConfig
  Generator.emit(args, () => new AxiBridgeCfg()(config))
}

/** AxiReorder standalone top. */
object AxiReorderTop extends App {
  // Default library params; buffer = outstanding*2 with outstanding=8 (BridgeCfg style).
  // Constraint: log2Ceil(buffer) <= idBits so entry index fits outbound ID.
  val axiParams = AxiParams()
  val buffer = 64
  Generator.emit(args, () => new AxiReorder(axiParams, buffer))
}

/** AxiBuffer standalone top. */
object AxiBufferTop extends App {
  val axiParams = AxiParams()
  val depth = 2
  Generator.emit(args, () => new AxiBuffer(axiParams, depth))
}

/** AxiBufferChain standalone top. */
object AxiBufferChainTop extends App {
  val axiParams = AxiParams()
  val chain = 2
  Generator.emit(args, () => new AxiBufferChain(axiParams, chain))
}

/** AxiFieldAdapter standalone top (same dataBits, pass-through ID). */
object AxiFieldAdapterTop extends App {
  val inP = AxiParams()
  val outP = AxiParams()
  val outstanding = 32
  Generator.emit(args, () => new AxiFieldAdapter(inP, outP, outstanding))
}

/** AxiNarrowToWide standalone top (mst narrow -> slv wide). */
object AxiNarrowToWideTop extends App {
  val mstParams = AxiParams(dataBits = 128)
  val slvParams = AxiParams(dataBits = 256)
  val buffer = 8
  Generator.emit(args, () => new AxiNarrowToWide(mstParams, slvParams, buffer))
}

/** AxiWideToNarrow standalone top (mst wide -> slv narrow). */
object AxiWideToNarrowTop extends App {
  val mstParams = AxiParams(dataBits = 256)
  val slvParams = AxiParams(dataBits = 128)
  val buffer = 8
  Generator.emit(args, () => new AxiWideToNarrow(mstParams, slvParams, buffer))
}

/** AxiErrorDevice standalone top. */
object AxiErrorDeviceTop extends App {
  val axiParams = AxiParams()
  Generator.emit(args, () => new AxiErrorDevice(axiParams))
}

/** AxiLite2Axi standalone top (requires lastBits == 0). */
object AxiLite2AxiTop extends App {
  val axiParams = new AxiLiteParams(addrBits = 48, dataBits = 64)
  Generator.emit(args, () => new AxiLite2Axi(axiParams))
}

/** AxiAsyncSource standalone top. */
object AxiAsyncSourceTop extends App {
  val axiP = AxiParams()
  val asyncP = AsyncQueueParams()
  Generator.emit(args, () => new AxiAsyncSource(axiP, asyncP))
}

/** AxiAsyncSink standalone top. */
object AxiAsyncSinkTop extends App {
  val axiP = AxiParams()
  val asyncP = AsyncQueueParams()
  Generator.emit(args, () => new AxiAsyncSink(axiP, asyncP))
}

// Backward-compatible aliases for older xmake / mill invocations.
object LmssGenerator extends App {
  LmssTop.main(args)
}

object AxiBridgeCfgGenerator extends App {
  AxiBridgeCfgTop.main(args)
}
