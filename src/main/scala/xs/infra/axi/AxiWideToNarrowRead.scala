// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi


import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import xs.utils.queue.MimoQueue
import chisel3.experimental.noPrefix
import chisel3.experimental.BundleLiterals._
import xs.utils.{CircularQueuePtr, HasCircularQueuePtrHelper}
import xs.utils.queue.RegInQueue
import xs.utils.queue.FastQueue
import xs.infra.axi.AxiComputeFunction.greaterAllones
import xs.utils.sram.DualPortSramTemplate

class PipeArInfo(mstParams: AxiParams) extends Bundle {
  val range        = UInt(12.W)
  val addrPfx      = UInt((mstParams.addrBits - 12).W)
  val addrSfx      = UInt(12.W)
  val byteMask     = UInt(12.W)
  val bits         = new AxiBundle(mstParams).ar.bits

  def getRange[T<:ARFlit](ar:T): PipeArInfo = {
    this.range    := Mux(AxiComputeFunction.isFix(ar.burst), 1.U(8.W) << ar.size, (ar.len + 1.U) << ar.size)
    this.addrPfx  := ar.addr(mstParams.addrBits - 1, 12)
    this.addrSfx  := (ar.addr(11, 0) >> ar.size) << ar.size
    this.byteMask := AxiComputeFunction.getMask(ar.len, ar.size)
    this.bits     := ar
    this
  }
}

class ArInfo(mstParams: AxiParams, slvParams: AxiParams) extends Bundle {
  val addrPfx       = UInt((mstParams.addrBits - 12).W)
  val addrSfx       = UInt(12.W)
  val endAddrSfx    = UInt(12.W)
  val startAddrSfx  = UInt(12.W)
  val originSfx     = UInt(12.W)
  val count         = UInt((mstParams.lenBits + 1).W)
  val wrapFlip      = Bool()
  val arinfo        = new AxiBundle(mstParams).ar.bits

  def getArResult[T <: PipeArInfo](ar:T): ArInfo = {
    val maxSlvIncrBits   = if(log2Ceil(slvParams.dataBits/8 * 256) > 12) 12 else log2Ceil(slvParams.dataBits/8 * 256)
    val incrShift        = (ar.range >> maxSlvIncrBits).asTypeOf(UInt(12.W))
    val incrCnt          = Mux(incrShift === 0.U, 1.U(12.W), incrShift)
    val wrapCnt          = Mux(ar.addrSfx(maxSlvIncrBits - 1, 0) === 0.U || (ar.byteMask & ar.addrSfx) === 0.U, incrCnt, incrCnt + 1.U)
    this.arinfo         := ar.bits
    this.addrPfx        := ar.addrPfx
    this.addrSfx        := ar.addrSfx
    this.originSfx      := ar.addrSfx
    this.count          := Mux(AxiComputeFunction.isFix(ar.bits.burst), ar.bits.len + 1.U, Mux(AxiComputeFunction.isWrap(ar.bits.burst), wrapCnt, incrCnt))
    this.endAddrSfx     := Mux(AxiComputeFunction.isWrap(ar.bits.burst), (ar.addrSfx & ~ar.byteMask) + ar.range, ar.addrSfx + ar.range)
    this.startAddrSfx   := Mux(AxiComputeFunction.isFix(ar.bits.burst), ar.addrSfx, ar.addrSfx & ~ar.byteMask)
    this.wrapFlip       := false.B
    this
  }
}

class RSplitBundle(mstParams: AxiParams, buffer: Int) extends Bundle {
  val id                = UInt(mstParams.idBits.W)
  val spiltLast         = Bool()
  val nid               = UInt(log2Ceil(buffer).W)
  val nextHit           = Bool()
  val originSize        = UInt(3.W)
  val valid             = Bool()
}

class AxiWideToNarrowRead(mstParams: AxiParams, slvParams: AxiParams, buffer:Int) extends Module with HasCircularQueuePtrHelper{
  override val desiredName = s"AxiWidthWriteCvt${mstParams.dataBits}To${slvParams.dataBits}"
  private val arPipeBuffer = 2
  private class CirQAxiEntryPtr extends CircularQueuePtr[CirQAxiEntryPtr](arPipeBuffer)
  private object CirQAxiEntryPtr {
  def apply(f: Bool, v: UInt): CirQAxiEntryPtr = {
        val ptr = Wire(new CirQAxiEntryPtr)
        ptr.flag := f
        ptr.value := v
        ptr
    }
  }
  val io = IO(new Bundle {
    val uAr = Flipped(new AxiBundle(mstParams).ar)
    val uR  = Flipped(new AxiBundle(mstParams).r)
    val dAr = new AxiBundle(slvParams).ar
    val dR  = new AxiBundle(slvParams).r
  })

  private val mdw = mstParams.dataBits
  private val sdw = slvParams.dataBits
  private val seg = mdw / sdw
  require(sdw < mdw)
  private val memSize         = buffer * mdw
  private val memUseSram      = memSize > 1024
  private val maxSlvSize      = log2Ceil(sdw / 8)
  private val maxSlvIncrRange = if(256 * sdw / 8 > 4096) 4096 else 256 * sdw / 8
  private val incrShiftBits   = log2Ceil(maxSlvIncrRange)

/* 
 * Register declaration
 */
  private val arinfo      = Reg(Vec(arPipeBuffer, new ArInfo(mstParams, slvParams)))
  private val arPipeQueue = Module(new FastQueue(new PipeArInfo(mstParams), 2))
  private val rq          = Module(new Queue(UInt(log2Ceil(buffer).W), 1, pipe = true))
  private val rHeadPtr    = RegInit(CirQAxiEntryPtr(f = false.B, v = 0.U))
  private val rTailPtr    = RegInit(CirQAxiEntryPtr(f = false.B, v = 0.U))

  private val mrgMskVec     = Reg(Vec(buffer, Vec(seg, Bool())))
  private val spiltCtrlVec  = RegInit(VecInit(Seq.fill(buffer)((new RSplitBundle(mstParams, buffer)).Lit(_.valid -> false.B))))
  private val maxNid        = Fill(log2Ceil(buffer), true.B)

  private val ctrlFreeVec   = VecInit(spiltCtrlVec.map(_.valid))
  // Match without fire so RREADY can stall until exactly one entry is hittable.
  private val rCandVec      = VecInit(spiltCtrlVec.map(e => e.valid && e.nextHit && e.id === io.dR.bits.id))
  private val rHitVec       = VecInit(rCandVec.map(_ && io.dR.fire))
  private val freeSel       = PickOneLow(ctrlFreeVec)
  private val arSameIdVec   = VecInit(spiltCtrlVec.zipWithIndex.map{case(e, i) => e.valid && e.id === io.dAr.bits.id && !(rHitVec(i) && io.dR.bits._last)})
  private val nextHitVec    = VecInit(spiltCtrlVec.map(c => c.valid && c.nid === 1.U && c.id === io.dR.bits.id && io.dR.fire && io.dR.bits._last))

  private val arPipeInfo    = WireInit(0.U.asTypeOf(new PipeArInfo(mstParams)))
  private val arSpiltDone   = (arinfo(rTailPtr.value).count === 1.U || arinfo(rTailPtr.value).arinfo.size <= maxSlvSize.U) && io.dAr.fire
  private val rdcNidVec     = VecInit(spiltCtrlVec.map(c => c.id === io.dR.bits.id && c.valid))
  private val rdcNidRegVec  = RegNext(rdcNidVec)
  private val rNidRdcReg    = RegNext(io.dR.bits._last && io.dR.fire)
  private val setNidEnable  = RegNext(io.dAr.fire)
  private val setNidEntry   = RegEnable(freeSel, io.dAr.fire)
  private val arSameIdReg   = RegEnable(arSameIdVec, io.dAr.fire)
  private val mem           = Mem(buffer, Vec(seg, UInt(sdw.W)))


/* 
 * Logic
 */
  for( i <- arinfo.indices) noPrefix {
    val arRcvHit   = WireInit(arPipeQueue.io.deq.fire && rHeadPtr.value === i.U)
    val arFireHit  = WireInit(io.dAr.fire && rTailPtr.value === i.U)
    val rangeToEnd = WireInit(arinfo(i).endAddrSfx - arinfo(i).addrSfx)
    arRcvHit.suggestName(s"ar_receive_info_hit_$i")
    arFireHit.suggestName(s"ar_fire_info_hit_$i")
    rangeToEnd.suggestName(s"ar_range_to_end_$i")
    
    when(arRcvHit) {
      arinfo(i).getArResult(arPipeQueue.io.deq.bits)
    }
    when(arFireHit) {
      arinfo(i).count  := arinfo(i).count - 1.U
    }
    when(arFireHit) {
      arinfo(i).addrSfx     := Mux(rangeToEnd <= maxSlvIncrRange.U, arinfo(i).startAddrSfx, arinfo(i).addrSfx + maxSlvIncrRange.U)
      arinfo(i).wrapFlip    := Mux(rangeToEnd <= maxSlvIncrRange.U, true.B, arinfo(i).wrapFlip)
      arinfo(i).endAddrSfx  := Mux(rangeToEnd <= maxSlvIncrRange.U && AxiComputeFunction.isWrap(arinfo(i).arinfo.burst), arinfo(i).originSfx, arinfo(i).endAddrSfx)
    }
  }
  for(i <- spiltCtrlVec.indices) noPrefix {
    val arFireCtrlHit   = WireInit(io.dAr.fire && freeSel.bits(i))
    val setNidHit       = WireInit(setNidEnable && setNidEntry.bits(i))
    arFireCtrlHit.suggestName(s"ar_ctrl_hit_$i")
    setNidHit.suggestName(s"set_nid_hit_$i")

    when(arFireCtrlHit) {
      spiltCtrlVec(i).id         := io.dAr.bits.id
      spiltCtrlVec(i).valid      := true.B
      spiltCtrlVec(i).spiltLast  := arinfo(rTailPtr.value).count === 1.U || ( arinfo(rTailPtr.value).arinfo.size <= maxSlvSize.U)
      spiltCtrlVec(i).originSize := arinfo(rTailPtr.value).arinfo.size
      spiltCtrlVec(i).nid        := maxNid
      spiltCtrlVec(i).nextHit    := false.B
    }
    when(rHitVec(i) && io.dR.bits._last) {
      spiltCtrlVec(i).valid      := false.B
    }
    when(nextHitVec(i)) {
      spiltCtrlVec(i).nextHit    := true.B
    }
    when(rNidRdcReg && rdcNidRegVec(i) && spiltCtrlVec(i).nid =/= 0.U) {
      spiltCtrlVec(i).nid        := spiltCtrlVec(i).nid - 1.U
    }
    when(setNidHit) {
      spiltCtrlVec(i).nid        := PopCount(arSameIdReg)
      spiltCtrlVec(i).nextHit    := Mux(PopCount(arSameIdReg) === 0.U, true.B, false.B)
    }
  }
  
  for(i <- mrgMskVec.indices) {
    for(j <- mrgMskVec(i).indices) {
      when(io.dAr.fire && freeSel.bits(i)) {
        mrgMskVec(i)(j)   := io.dAr.bits.addr(log2Ceil(mdw/8) - 1, log2Ceil(sdw/8)) === j.U
      }.elsewhen(rHitVec(i)) {
        mrgMskVec(i)(j)   := mrgMskVec(i)((j + seg - 1) % seg)
      }
    }
  }

  private val arTailInfo    = arinfo(rTailPtr.value)
  rHeadPtr                 := Mux(arPipeQueue.io.deq.fire, rHeadPtr + 1.U, rHeadPtr)
  rTailPtr                 := Mux(arSpiltDone            , rTailPtr + 1.U, rTailPtr)
  private val incrNum       = (( (arTailInfo.endAddrSfx - arTailInfo.addrSfx)   >> maxSlvSize ) - 1.U).asTypeOf(UInt(12.W))
  private val slvArBits     = WireInit(0.U.asTypeOf(io.dAr.bits.cloneType))
  slvArBits                := arTailInfo.arinfo
  slvArBits.addr           := Cat(arTailInfo.addrPfx, arTailInfo.addrSfx)
  slvArBits.size           := maxSlvSize.U
  slvArBits.burst          := AxiComputeFunction.INCR
  slvArBits.len            := Mux(greaterAllones(incrNum, 255), 255.U, incrNum)

  private val rwd          = VecInit(Seq.fill(seg)(io.dR.bits.data))
  private val rwa          = OHToUInt(rHitVec)
  private val rwm          = mrgMskVec(rwa)
  when(io.dR.fire) {
    mem.write(rwa, rwd, rwm)
  }

  private val mergeDone    = RegEnable(mrgMskVec(rwa).last && (spiltCtrlVec(rwa).originSize > maxSlvSize.U), io.dR.fire)
  private val noMrgRFire   = RegEnable(spiltCtrlVec(rwa).originSize <= maxSlvSize.U, io.dR.fire)
  private val rlast        = RegEnable(io.dR.bits._last && spiltCtrlVec(rwa).spiltLast, io.dR.fire)
  private val rid          = RegEnable(io.dR.bits.id.asTypeOf(UInt(io.uR.bits.id.getWidth.W)), io.dR.fire)
  private val rresp        = RegEnable(io.dR.bits.resp, io.dR.fire)
  private val ruser        = RegEnable(io.dR.bits.user, io.dR.fire)

/* 
 * IO Connection
 */
  arPipeQueue.io.enq.bits    := arPipeInfo.getRange(io.uAr.bits)
  arPipeQueue.io.enq.valid   := io.uAr.valid
  arPipeQueue.io.deq.ready   := !isFull(rHeadPtr, rTailPtr)

  rq.io.enq.valid            := io.dR.valid
  rq.io.enq.bits             := rwa
  rq.io.deq.ready            := rq.io.deq.valid && Mux(io.uR.ready, true.B, !mergeDone && !rlast && !noMrgRFire)

  io.uAr.ready               := arPipeQueue.io.enq.ready
  io.dAr.valid               := !isEmpty(rHeadPtr, rTailPtr) && freeSel.bits.orR
  io.dAr.bits                := Mux(arTailInfo.arinfo.size > maxSlvSize.U, slvArBits, arTailInfo.arinfo)
  io.dR.ready                := rq.io.enq.ready && rCandVec.asUInt.orR
  io.uR.bits.id              := rid
  io.uR.bits.last            := rlast
  io.uR.bits.data            := mem(rq.io.deq.bits(log2Ceil(buffer) - 1, 0)).asUInt
  io.uR.bits.user            := ruser
  io.uR.bits.resp            := rresp
  io.uR.valid                := rq.io.deq.valid && (mergeDone || rlast || noMrgRFire)

/* 
 * Assertion
 */
  when(rCandVec.asUInt.orR) {
    assert(PopCount(rCandVec) === 1.U, "rCandVec must be one-hot")
  }
  when(io.dR.fire) {
    assert(PopCount(rHitVec) === 1.U)
  }

}