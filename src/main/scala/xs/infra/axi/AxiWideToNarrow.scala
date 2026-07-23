// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi

import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import xs.utils.queue.MimoQueue
import chisel3.experimental.noPrefix

class AxiWideToNarrow(mstParams: AxiParams, slvParams: AxiParams, buffer:Int) extends Module {
  override val desiredName = s"AxiWidthCvt${mstParams.dataBits}To${slvParams.dataBits}"
  val io = IO(new Bundle {
    val mst = Flipped(new AxiBundle(mstParams))
    val slv = new AxiBundle(slvParams)
  })
  private val mdw = mstParams.dataBits
  private val sdw = slvParams.dataBits
  private val seg = mdw / sdw
  require(sdw <= mdw)
  // Width converter does not remap AXI IDs; both sides must use the same id width.
  require(mstParams.idBits == slvParams.idBits, "AxiWideToNarrow requires equal idBits")
  private val maxSlvSize = log2Ceil(sdw / 8)

  private val readLogicCtrl  = Module(new AxiWideToNarrowRead(mstParams, slvParams, buffer))
  private val writeLogicCtrl = Module(new AxiWideToNarrowWrite(mstParams, slvParams, buffer))

  io.slv.ar     <> readLogicCtrl.io.dAr
  io.slv.r      <> readLogicCtrl.io.dR
  io.slv.aw     <> writeLogicCtrl.io.dAw
  io.slv.w      <> writeLogicCtrl.io.dW
  io.slv.b      <> writeLogicCtrl.io.dB

  io.mst.ar     <> readLogicCtrl.io.uAr
  io.mst.aw     <> writeLogicCtrl.io.uAw
  io.mst.w      <> writeLogicCtrl.io.uW
  io.mst.b      <> writeLogicCtrl.io.uB
  io.mst.r      <> readLogicCtrl.io.uR
}
