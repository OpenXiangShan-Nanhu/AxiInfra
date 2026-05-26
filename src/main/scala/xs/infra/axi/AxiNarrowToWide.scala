// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi

import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import chisel3.experimental.noPrefix
import AxiComputeFunction._
import xs.utils.queue.{CircularQueuePtr, HasCircularQueuePtrHelper, FastQueue}

class AxiWidthWCvtBundle(axiP: AxiParams) extends Bundle {
  val addrSfx = UInt(12.W)
  val size     = UInt(axiP.sizeBits.W)
  val id       = UInt(axiP.idBits.W)
  val byteMask = UInt(12.W)

  def := (in: AWFlit): Unit = {
    this.addrSfx := in.addr(11, 0)
    this.size     := in.size
    this.id       := in.id
    this.byteMask := PriorityMux(Seq(
      isIncr(in.burst) -> 0xFFF.U,
      isWrap(in.burst) -> (getMask(in.len, in.size)),
      true.B           -> 0.U
    ))
  }
}


class AxiWidthRCvtBundle(axiP: AxiParams, outstanding: Int) extends Bundle {
  val addrSfx  = UInt(axiP.addrBits.W)
  val size     = UInt(axiP.sizeBits.W)
  val id       = UInt(axiP.idBits.W)
  val nid      = UInt(log2Ceil(outstanding).W)
  val byteMask = UInt(12.W)

  def := (in: ARFlit): Unit = {
    this.addrSfx := in.addr(11, 0)
    this.size     := in.size
    this.id       := in.id
    this.byteMask := PriorityMux(Seq(
      isIncr(in.burst) -> 0xFFF.U,
      isWrap(in.burst) -> (getMask(in.len, in.size)),
      true.B           -> 0.U
    ))
  }
}

class AxiNarrowToWide(mstParams: AxiParams, slvParams: AxiParams, buffer:Int) extends Module with HasCircularQueuePtrHelper {
  override val desiredName = s"AxiWidthCvt${mstParams.dataBits}To${slvParams.dataBits}"
  private class CirQAxiEntryPtr extends CircularQueuePtr[CirQAxiEntryPtr](buffer)
  require(mstParams.lastBits != 0)
  require(slvParams.lastBits != 0)
  private object CirQAxiEntryPtr {
  def apply(f: Bool, v: UInt): CirQAxiEntryPtr = {
        val ptr = Wire(new CirQAxiEntryPtr)
        ptr.flag := f
        ptr.value := v
        ptr
    }
  }
  val io = IO(new Bundle {
    val mst = Flipped(new AxiBundle(mstParams))
    val slv = new AxiBundle(slvParams)
  })
  private val mdw           = mstParams.dataBits
  private val sdw           = slvParams.dataBits
  private val seg           = sdw / mdw
  private val awinfo        = Reg(Vec(buffer, new AxiWidthWCvtBundle(mstParams)))
  private val wHeadPtr      = RegInit(CirQAxiEntryPtr(f = false.B, v = 0.U))
  private val wTailPtr      = RegInit(CirQAxiEntryPtr(f = false.B, v = 0.U))
  private val wq            = Module(new FastQueue(new WFlit(mstParams), size = 2))
  private val rq            = Module(new Queue(new RFlit(slvParams), entries = 1, pipe = true))
  private val arvld         = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val arinfo        = Reg(Vec(buffer, new AxiWidthRCvtBundle(mstParams, buffer)))
  private val arsel         = PickOneLow(arvld)
  private val infoSelOH     = Wire(Vec(buffer, Bool()))
  private val nidCalcVec    = Wire(Vec(buffer, Bool()))
  private val rawNid        = PopCount(nidCalcVec)
  private val cncrtWkVld    = io.mst.r.fire && io.mst.ar.fire && io.mst.r.bits._last && io.mst.r.bits.id === io.mst.ar.bits.id
  private val cncrtWkVldReg = RegNext(cncrtWkVld)
  private val cncrtWkEtrReg = RegEnable(arsel.bits, cncrtWkVld)
  require(mdw <= sdw)
  require(slvParams.idBits >= log2Ceil(buffer).max(mstParams.idBits))

  for(i <- arvld.indices) noPrefix {
    val rFireMayHit = WireInit(io.mst.r.valid && io.mst.r.ready && io.mst.r.bits.id === arinfo(i).id && arvld(i))
    rFireMayHit.suggestName(s"r_fire_may_hit_$i")
    val arFireHit = WireInit(io.mst.ar.fire && arsel.bits(i))
    arFireHit.suggestName(s"ar_fire_hit_$i")

    when(arFireHit) {
      arvld(i) := true.B
    }.elsewhen(rFireMayHit && io.mst.r.bits._last && arinfo(i).nid === 0.U) {
      arvld(i) := false.B
    }
    when(arFireHit) {
      arinfo(i).nid   := rawNid
    }.elsewhen((cncrtWkVldReg && cncrtWkEtrReg(i)) && (rFireMayHit && io.mst.r.bits._last && arinfo(i).nid =/= 0.U)) {
      arinfo(i).nid   := arinfo(i).nid - 2.U
    }.elsewhen((cncrtWkVldReg && cncrtWkEtrReg(i)) || (rFireMayHit && io.mst.r.bits._last && arinfo(i).nid =/= 0.U)) {
      arinfo(i).nid   := arinfo(i).nid - 1.U
    }
    when(arFireHit) {
      arinfo(i)  := io.mst.ar.bits
    }
    when(rFireMayHit && arinfo(i).nid === 0.U) {
      arinfo(i).addrSfx := getNext(arinfo(i).addrSfx, arinfo(i).size, arinfo(i).byteMask)
    }

    infoSelOH(i)     := arvld(i) && arinfo(i).id === io.mst.r.bits.id && arinfo(i).nid === 0.U
    nidCalcVec(i)    := arvld(i) && arinfo(i).id === io.mst.ar.bits.id
  }
  //AW Channel Connection
  when(io.mst.aw.fire) {
    awinfo(wHeadPtr.value) := io.mst.aw.bits
    wHeadPtr               := wHeadPtr + 1.U
  }
  io.slv.aw.valid := io.mst.aw.valid && !isFull(wHeadPtr, wTailPtr)
  io.slv.aw.bits := io.mst.aw.bits
  io.mst.aw.ready := io.slv.aw.ready && !isFull(wHeadPtr, wTailPtr)

  //AR Channel Connection
  io.slv.ar.valid := io.mst.ar.valid && arsel.valid
  io.slv.ar.bits := io.mst.ar.bits
  io.mst.ar.ready := io.slv.ar.ready && arsel.valid

  //W Channel Connection
  private val awinfoTail = awinfo(wTailPtr.value)
  private val strb = Wire(Vec(seg, UInt((mdw / 8).W)))
  private val waddrcvt = if(sdw > mdw) awinfoTail.addrSfx(log2Ceil(sdw / 8) - 1, log2Ceil(mdw / 8)) else 0.U
  strb.zipWithIndex.foreach({case(s, i) => s := Mux(waddrcvt === i.U, wq.io.deq.bits.strb, 0.U)})

  wq.io.enq <> io.mst.w
  io.slv.w.valid := wq.io.deq.valid && !isEmpty(wHeadPtr, wTailPtr)
  io.slv.w.bits := wq.io.deq.bits
  io.slv.w.bits.data := Fill(seg, wq.io.deq.bits.data)
  io.slv.w.bits.strb := strb.asUInt
  wq.io.deq.ready := io.slv.w.ready && !isEmpty(wHeadPtr, wTailPtr)
  when(wq.io.deq.valid && io.slv.w.ready && !isEmpty(wHeadPtr, wTailPtr)) {
    awinfoTail.addrSfx := getNext(awinfoTail.addrSfx, awinfoTail.size, awinfoTail.byteMask)
  }
  when(wq.io.deq.valid && io.slv.w.ready && wq.io.deq.bits._last && !isEmpty(wHeadPtr, wTailPtr)) {
    wTailPtr := wTailPtr + 1.U
  }

  //B Channel Connection
  io.mst.b <> io.slv.b

  //R Channel Connection
  private val infoSel = Mux1H(infoSelOH, arinfo)
  private val raddrcvt = if(sdw > mdw) infoSel.addrSfx(log2Ceil(sdw / 8) - 1, log2Ceil(mdw / 8)) else 0.U
  private val rdata = rq.io.deq.bits.data.asTypeOf(Vec(seg, UInt(mdw.W)))

  rq.io.enq <> io.slv.r
  io.mst.r  <> rq.io.deq
  io.mst.r.bits.data := rdata(raddrcvt)

  when(io.mst.r.fire) {
    assert(PopCount(infoSelOH) === 1.U, s"Multiple R entries are hit!")
  }
}