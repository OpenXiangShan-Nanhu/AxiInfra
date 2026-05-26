// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi

import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import xs.utils.queue.MimoQueue
import xs.utils.queue.FastQueue
import chisel3.experimental.noPrefix
import chisel3.experimental.BundleLiterals._
import xs.utils.{CircularQueuePtr, HasCircularQueuePtrHelper}
import xs.infra.axi.AxiComputeFunction.greaterAllones

class AwInfo(mstParams: AxiParams, slvParams: AxiParams) extends Bundle {
  val addrPfx      = UInt((mstParams.addrBits - 12).W)
  val addrSfx      = UInt(12.W)
  val endAddrSfx   = UInt(12.W)
  val startAddrSfx = UInt(12.W)
  val originSfx    = UInt(12.W)
  val count        = UInt((mstParams.lenBits + 1).W)
  val wrapFlip     = Bool()
  val awinfo       = new AxiBundle(mstParams).aw.bits

  def getAwResult[T <: PipeAwInfo](aw: T): AwInfo = {
    val maxSlvIncrBits   = if(log2Ceil(slvParams.dataBits/8 * 256) > 12) 12 else log2Ceil(slvParams.dataBits/8 * 256)
    val incrShift        = (aw.range >> maxSlvIncrBits).asTypeOf(UInt(12.W))
    val incrCnt          = Mux(incrShift === 0.U, 1.U(12.W), incrShift).asTypeOf(UInt(12.W))
    val wrapCnt          = Mux((aw.addrSfx(maxSlvIncrBits - 1, 0) === 0.U) || ((aw.byteMask & aw.addrSfx) === 0.U), incrCnt, incrCnt + 1.U)
    this.awinfo         := aw.bits
    this.addrPfx        := aw.addrPfx
    this.addrSfx        := aw.addrSfx
    this.originSfx      := aw.addrSfx
    this.count          := Mux(AxiComputeFunction.isFix(aw.bits.burst), aw.bits.len + 1.U, Mux(AxiComputeFunction.isWrap(aw.bits.burst), wrapCnt, incrCnt))
    this.endAddrSfx     := Mux(AxiComputeFunction.isWrap(aw.bits.burst), (aw.addrSfx & ~aw.byteMask) + aw.range, aw.addrSfx + aw.range)
    this.startAddrSfx   := Mux(AxiComputeFunction.isFix(aw.bits.burst), aw.addrSfx, aw.addrSfx & ~aw.byteMask)
    this.wrapFlip       := false.B
    this
  }
}

class binfo(mstParams: AxiParams, buffer: Int) extends Bundle {
    val originId   = UInt(mstParams.idBits.W)
    val rcvBNum    = UInt(mstParams.lenBits.W)
    val bNid       = UInt(log2Ceil(buffer).W)
    val nextHit    = Bool()
    val valid      = Bool()
}

class PipeAwInfo(mstParams: AxiParams) extends Bundle {
  val range        = UInt(12.W)
  val addrPfx      = UInt((mstParams.addrBits - 12).W)
  val addrSfx      = UInt(12.W)
  val byteMask     = UInt(12.W)
  val bits         = new AxiBundle(mstParams).aw.bits
  
  def getRange[T<:AWFlit](aw: T): PipeAwInfo = {
    this.range    := Mux(AxiComputeFunction.isFix(aw.burst), 1.U(8.W) << aw.size, ( aw.len + 1.U ) << aw.size)
    this.addrPfx  := aw.addr(mstParams.addrBits - 1, 12)
    this.addrSfx  := (aw.addr(11, 0) >> aw.size) << aw.size
    this.byteMask := AxiComputeFunction.getMask(aw.len, aw.size)
    this.bits     := aw
    this
  }
}

class awShiftBundle(mstParams: AxiParams) extends Bundle {
  val addr    = UInt(12.W)
  val size    = UInt(mstParams.sizeBits.W)
  val burst   = UInt(mstParams.burstBits.W)
}

class AxiWideToNarrowWrite(mstParams: AxiParams, slvParams: AxiParams, buffer:Int) extends Module with HasCircularQueuePtrHelper{
  override val desiredName = s"AxiWidthWriteCvt${mstParams.dataBits}To${slvParams.dataBits}"
  private val awPipeBuffer = 2
  private class CirQAxiEntryPtr extends CircularQueuePtr[CirQAxiEntryPtr](awPipeBuffer)
  private object CirQAxiEntryPtr {
  def apply(f: Bool, v: UInt): CirQAxiEntryPtr = {
        val ptr = Wire(new CirQAxiEntryPtr)
        ptr.flag := f
        ptr.value := v
        ptr
    }
  }
  private class CirQWEntryPtr extends CircularQueuePtr[CirQWEntryPtr](buffer)
  private object CirQWEntryPtr {
  def apply(f: Bool, v: UInt): CirQWEntryPtr = {
        val ptr = Wire(new CirQWEntryPtr)
        ptr.flag := f
        ptr.value := v
        ptr
    }
  }
  val io = IO(new Bundle {
    val uAw = Flipped(new AxiBundle(mstParams).aw)
    val uW  = Flipped(new AxiBundle(mstParams).w)
    val uB  = Flipped(new AxiBundle(mstParams).b)
    val dAw = new AxiBundle(slvParams).aw
    val dW  = new AxiBundle(slvParams).w
    val dB  = new AxiBundle(slvParams).b
  })

  private val mdw = mstParams.dataBits
  private val sdw = slvParams.dataBits
  private val seg = mdw / sdw
  require(sdw < mdw)
  private val maxSlvSize      = log2Ceil(sdw / 8)
  private val maxSlvIncrRange = if(256 * sdw / 8 > 4096) 4096 else 256 * sdw / 8
  private val incrShiftBits   = log2Ceil(maxSlvIncrRange)
  private val shiftHigh       = log2Ceil(mdw / 8) - 1
  private val shiftLow        = log2Ceil(sdw / 8)

/* 
 * Register declaration
 */
  private val awinfo      = Reg(Vec(awPipeBuffer, new AwInfo(mstParams, slvParams)))  
  private val awPipeQueue = Module(new FastQueue(new PipeAwInfo(mstParams), 2))
  private val wCounter    = RegInit(0.U(mstParams.lenBits.W))
  private val wq          = Module(new MimoQueue(new WFlit(slvParams), seg, 1, buffer * seg, false))
  private val binfo       = RegInit(VecInit(Seq.fill(buffer) {new binfo(mstParams, buffer).Lit(_.valid -> false.B)}))
  private val wHeadPtr    = RegInit(CirQAxiEntryPtr(f = false.B, v = 0.U))
  private val wTailPtr    = RegInit(CirQAxiEntryPtr(f = false.B, v = 0.U))
  private val wLastCtlQ   = Module(new FastQueue(UInt((mstParams.lenBits).W), buffer))
  private val wCtrlQ      = Module(new FastQueue(Bool(), buffer))
  private val awAddrInfo  = Reg(Vec(buffer, new awShiftBundle(mstParams)))
  private val awHeadPtr   = RegInit(CirQWEntryPtr(f = false.B, v = 0.U))
  private val awTailPtr   = RegInit(CirQWEntryPtr(f = false.B, v = 0.U))

  private val awPipeInfo  = WireInit(0.U.asTypeOf(new PipeAwInfo(mstParams)))
  private val bVldVec     = binfo.map(_.valid)
  private val bFreeVec    = binfo.map(!_.valid)
  private val bSel        = PriorityEncoder(bFreeVec)
  private val maxNid      = Fill(log2Ceil(buffer), true.B)

  val maxSize             = 7
  val maskTable           = VecInit((0 until maxSize).map(i => ((1L << i) - 1).U(maxSize.W)))

  // b resp logic
  private val bHitVec     = VecInit(binfo.map(b => b.valid && b.nextHit && b.originId === io.dB.bits.id))
  private val bSameIdVec  = VecInit(binfo.zipWithIndex.map{case(b, i) => b.valid && b.originId === awPipeQueue.io.deq.bits.bits.id && !(bHitVec(i) & io.dB.fire)})
  private val bRdcNidVec  = VecInit(binfo.zipWithIndex.map{case(b, i) => b.valid && b.originId === io.dB.bits.id})
  private val rdcNidReg   = RegEnable(bRdcNidVec, io.dB.fire)
  private val setHitVec   = VecInit(binfo.map(c => c.valid && c.bNid === 1.U && c.originId === io.dB.bits.id && io.dB.fire))
  private val bSameIdReg  = RegEnable(bSameIdVec, awPipeQueue.io.deq.fire)
  private val bNidSetEn   = RegNext(awPipeQueue.io.deq.fire)
  private val bRdcNidEn   = RegNext(io.dB.fire)
  private val bSelNextReg = RegNext(bSel, 0.U)

  for(i <- awinfo.indices) noPrefix {
    val awRcvHit  = WireInit(awPipeQueue.io.deq.fire && wHeadPtr.value === i.U)
    val awFireHit = WireInit(io.dAw.fire && wTailPtr.value === i.U)
    val rangeToEnd = WireInit(awinfo(i).endAddrSfx - awinfo(i).addrSfx)
    awRcvHit.suggestName(s"aw_receive_info_hit_$i")
    awFireHit.suggestName(s"aw_fire_info_hit_$i")
    rangeToEnd.suggestName(s"aw_range_to_end_$i")

    when(awRcvHit) {
      awinfo(i).getAwResult(awPipeQueue.io.deq.bits)
    }
    when(awFireHit) {
      awinfo(i).count    := awinfo(i).count - 1.U
    }
    when(awFireHit) {
      awinfo(i).addrSfx    := Mux(rangeToEnd <= maxSlvIncrRange.U, awinfo(i).startAddrSfx, awinfo(i).addrSfx + maxSlvIncrRange.U)
      awinfo(i).wrapFlip   := Mux(rangeToEnd <= maxSlvIncrRange.U, true.B, awinfo(i).wrapFlip)
      awinfo(i).endAddrSfx := Mux(rangeToEnd <= maxSlvIncrRange.U && AxiComputeFunction.isWrap(awinfo(i).awinfo.burst), awinfo(i).originSfx, awinfo(i).endAddrSfx)
    }
  }
  when(awPipeQueue.io.deq.fire) {
    val burst             = awPipeQueue.io.deq.bits.bits.burst
    val len               = awPipeQueue.io.deq.bits.bits.len
    val addrSfx           = awPipeQueue.io.deq.bits.addrSfx
    val size              = awPipeQueue.io.deq.bits.bits.size
    val incrShift         = (awPipeQueue.io.deq.bits.range >> incrShiftBits).asTypeOf(UInt(12.W))
    val byteMask          = awPipeQueue.io.deq.bits.byteMask
    val incrCnt           = Mux(incrShift === 0.U, 1.U(12.W), incrShift)
    val wrapCnt           = Mux(addrSfx(incrShiftBits - 1, 0) === 0.U || (byteMask & addrSfx) === 0.U, incrCnt, incrCnt + 1.U)
    binfo(bSel).originId := awPipeQueue.io.deq.bits.bits.id
    binfo(bSel).bNid     := maxNid
    binfo(bSel).rcvBNum  := Mux(AxiComputeFunction.isFix(burst), len + 1.U, Mux(AxiComputeFunction.isWrap(burst), wrapCnt, incrCnt))
    binfo(bSel).nextHit  := false.B
    binfo(bSel).rcvBNum  := PriorityMux(Seq(
      (size <= maxSlvSize.U)            -> 1.U,
      AxiComputeFunction.isFix(burst)  -> (len + 1.U),
      AxiComputeFunction.isWrap(burst) -> (wrapCnt),
      true.B                           -> incrCnt
    ))
    binfo(bSel).valid    := true.B
  }

  for(i <- binfo.indices) noPrefix {
    val setNidHit  = WireInit(bNidSetEn && bSelNextReg === i.U)
    val bNumRdc    = WireInit(io.dB.fire && bHitVec(i))
    setNidHit.suggestName(s"b_set_nid_hit_$i")
    bNumRdc.suggestName(s"b_reduce_rcvnum_$i")
    when(setNidHit) {
     binfo(i).bNid    := PopCount(bSameIdReg)
     binfo(i).nextHit := Mux(PopCount(bSameIdReg) === 0.U, true.B, false.B)
    }
    when(bHitVec(i) && io.dB.fire && binfo(i).rcvBNum === 1.U) {
      binfo(i).valid := false.B
    }
    when(bRdcNidEn && rdcNidReg(i) && binfo(i).bNid =/= 0.U) {
      binfo(i).bNid  := binfo(i).bNid - 1.U
    }
    when(setHitVec(i)){
      binfo(i).nextHit := true.B
    }
    when(bNumRdc) {
      binfo(i).rcvBNum := binfo(i).rcvBNum - 1.U
    }
  }

  private val slvAwBits    = WireInit(0.U.asTypeOf(io.dAw.bits.cloneType))
  private val awTailInfo   = awinfo(wTailPtr.value)
  private val incrNum      = (( (awTailInfo.endAddrSfx - awTailInfo.addrSfx) >> maxSlvSize ) - 1.U).asTypeOf(UInt(12.W))

  slvAwBits               := awTailInfo.awinfo
  slvAwBits.addr          := Cat(awTailInfo.addrPfx, awTailInfo.addrSfx)
  slvAwBits.size          := maxSlvSize.U
  slvAwBits.burst         := AxiComputeFunction.INCR
  slvAwBits.len           := Mux(greaterAllones(incrNum, 255), 255.U, incrNum)

  private val wdv = io.uW.bits.data.asTypeOf(Vec(seg, UInt(sdw.W)))
  private val wmv = io.uW.bits.strb.asTypeOf(Vec(seg, UInt((sdw / 8).W)))
  private val enqv = Wire(Vec(seg, Bool()))
  private val enqLastVec = PriorityEncoderOH(enqv.reverse).reverse
  private val awAddrBits = awAddrInfo(awTailPtr.value)
  private val lowIdx     = awAddrBits.addr(shiftHigh, shiftLow)
  private val range      = Mux(awAddrBits.size <= maxSlvSize.U, 0.U, maskTable(awAddrBits.size - maxSlvSize.U))
  private val highIdx    = awAddrBits.addr(shiftHigh, shiftLow) + range
  for(i <- wq.io.enq.indices) {
    enqv(i) := (i.U >= lowIdx) && (i.U <= highIdx)
    wq.io.enq(i).valid := io.uW.fire && enqv(i)
    wq.io.enq(i).bits.data := wdv(i)
    wq.io.enq(i).bits.strb := wmv(i)
    wq.io.enq(i).bits.user := io.uW.bits.user
    wq.io.enq(i).bits.last := io.uW.bits._last && enqLastVec(i)
  }
  when(io.dW.fire && !io.dW.bits._last) {
    wCounter := wCounter + 1.U
  }.elsewhen(io.dW.fire && io.dW.bits._last) {
    wCounter := 0.U
  }

  when(io.uAw.fire) {
    awAddrInfo(awHeadPtr.value).addr  := ((io.uAw.bits.addr(11, 0) >> io.uAw.bits.size) << io.uAw.bits.size)(11, 0)
    awAddrInfo(awHeadPtr.value).burst := io.uAw.bits.burst
    awAddrInfo(awHeadPtr.value).size  := io.uAw.bits.size
    awHeadPtr                         := awHeadPtr + 1.U
  }

  when(io.uW.fire) {
    awAddrInfo(awTailPtr.value).addr  := Mux(AxiComputeFunction.isFix(awAddrInfo(awTailPtr.value).burst), awAddrInfo(awTailPtr.value).addr, awAddrInfo(awTailPtr.value).addr + (1.U << awAddrInfo(awTailPtr.value).size))
  }

  awTailPtr                := Mux(io.uW.bits._last && io.uW.fire, awTailPtr + 1.U, awTailPtr)

  wLastCtlQ.io.enq.valid   := io.dAw.fire
  wLastCtlQ.io.enq.bits    := io.dAw.bits.len
  wLastCtlQ.io.deq.ready   := io.dW.fire && (wCounter === wLastCtlQ.io.deq.bits)

  wCtrlQ.io.enq.valid      := io.uAw.fire
  wCtrlQ.io.enq.bits       := true.B
  wCtrlQ.io.deq.ready      := io.uW.fire && io.uW.bits._last

  awPipeQueue.io.enq.bits  := awPipeInfo.getRange(io.uAw.bits)
  awPipeQueue.io.enq.valid := io.uAw.valid && !isFull(awHeadPtr, awTailPtr) && wCtrlQ.io.enq.ready
  awPipeQueue.io.deq.ready := !isFull(wHeadPtr, wTailPtr) && bFreeVec.reduce(_ | _)

  private val awSpiltDone   = (awinfo(wTailPtr.value).count === 1.U || awinfo(wTailPtr.value).awinfo.size <= maxSlvSize.U) && io.dAw.fire
  wHeadPtr                 := Mux(awPipeQueue.io.deq.fire, wHeadPtr + 1.U, wHeadPtr)
  wTailPtr                 := Mux(awSpiltDone, wTailPtr + 1.U, wTailPtr)
  
  io.uAw.ready             := awPipeQueue.io.enq.ready && !isFull(awHeadPtr, awTailPtr) && wCtrlQ.io.enq.ready
  io.uW.ready              := wLastCtlQ.io.deq.valid && wCtrlQ.io.deq.valid && ((buffer * seg).U -  wq.io.count) >= PopCount(enqv)
  io.dAw.valid             := !isEmpty(wHeadPtr, wTailPtr) && wLastCtlQ.io.enq.ready
  io.dAw.bits              := Mux(awTailInfo.awinfo.size > maxSlvSize.U, slvAwBits, awTailInfo.awinfo)
  io.dW.valid              := wq.io.deq.head.valid && wLastCtlQ.io.deq.valid
  io.dW.bits               := wq.io.deq.head.bits
  io.dW.bits.last          := wCounter === wLastCtlQ.io.deq.bits && wLastCtlQ.io.deq.valid
  io.uB.valid              := io.dB.valid && binfo(OHToUInt(bHitVec)).rcvBNum === 1.U
  io.uB.bits               := io.dB.bits
  io.dB.ready              := io.uB.ready

  wq.io.deq.head.ready     := io.dW.ready && wLastCtlQ.io.deq.valid

/* 
 * Assertion
 */
  when(io.dB.fire) {
    assert(binfo(OHToUInt(bHitVec)).valid)
    assert(PopCount(bHitVec) === 1.U)
  }
}