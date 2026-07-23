// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package generator

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, FirtoolOption}

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
}

object LmssGenerator extends App {
  chisel3.VerificationLayers.assertLayer = false
  chisel3.VerificationLayers.coverLayer = false
  chisel3.VerificationLayers.assumeLayer = false
  val config = new SubsysConfig
  (new ChiselStage).execute(args, Generator.firtoolOpts ++ Seq(
    ChiselGeneratorAnnotation(() => new AxiSubsysTop()(config))
  ))
}

/** Generate AxiBridgeCfg (CFG 桥, 1S×3M) top-level RTL. */
object AxiBridgeCfgGenerator extends App {
  chisel3.VerificationLayers.assertLayer = false
  chisel3.VerificationLayers.coverLayer = false
  chisel3.VerificationLayers.assumeLayer = false
  val config = new AxiBridgeCfgConfig
  (new ChiselStage).execute(args, Generator.firtoolOpts ++ Seq(
    ChiselGeneratorAnnotation(() => new AxiBridgeCfg()(config))
  ))
}