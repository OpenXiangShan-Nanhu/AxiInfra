package lmss.axi

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
  private val maxSlvSize = log2Ceil(sdw / 8)

  private val awvld = RegInit(false.B)
  private val awinfo = Reg(new AxiWidthWCvtBundle(mstParams))
  private val wq = Module(new MimoQueue(new WFlit(slvParams), seg, 1, buffer * seg, false))

  private val arvld = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val rvld  = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val arinfo = Reg(Vec(buffer, new AxiWidthRCvtBundle(mstParams, buffer)))
  private val mrgMskVec = Reg(Vec(buffer, Vec(seg, Bool())))
  private val rmem = Mem(buffer, Vec(seg, UInt(sdw.W)))
  private val rq = Module(new Queue(UInt(log2Ceil(buffer).W), 1, pipe = true))
  private val rlsq = Module(new Queue(UInt(log2Ceil(buffer).W), 1, pipe = true))


  // Write Splitting
  io.slv.aw <> io.mst.aw
  io.slv.w <> wq.io.deq.head
  io.slv.aw.valid := Mux(awvld, io.slv.w.fire && io.slv.w.bits._last, true.B) && io.mst.aw.valid
  io.mst.aw.ready := Mux(awvld, io.slv.w.fire && io.slv.w.bits._last, true.B) && io.slv.aw.ready
  when(io.slv.aw.fire) {
    awvld := true.B
  }.elsewhen(io.slv.w.fire && io.slv.w.bits._last) {
    awvld := false.B
  }

  when(io.slv.aw.fire) {
    awinfo := io.mst.aw.bits
  }

  private val wlenShift = io.mst.aw.bits.size - maxSlvSize.U
  private val oriAwLen = io.mst.aw.bits.len +& 1.U
  when(io.mst.aw.bits.size > maxSlvSize.U) {
    io.slv.aw.bits.size := maxSlvSize.U
    io.slv.aw.bits.len := (oriAwLen << wlenShift).asUInt - 1.U
  }

  io.mst.w.ready := awvld && Cat(wq.io.enq.map(_.ready)).andR
  private val wdv = io.mst.w.bits.data.asTypeOf(Vec(seg, UInt(sdw.W)))
  private val wmv = io.mst.w.bits.strb.asTypeOf(Vec(seg, UInt((sdw / 8).W)))
  private val enqv = Wire(Vec(seg, Bool()))
  private val enqLastVec = PriorityEncoderOH(enqv.reverse).reverse
  for(i <- wq.io.enq.indices) {
    enqv(i) := wmv(i).orR
    wq.io.enq(i).valid := io.mst.w.valid && awvld && enqv(i)
    wq.io.enq(i).bits.data := wdv(i)
    wq.io.enq(i).bits.strb := wmv(i)
    wq.io.enq(i).bits.user := io.mst.w.bits.user
    wq.io.enq(i).bits.last := io.mst.w.bits._last && enqLastVec(i)
  }

  io.mst.b <> io.slv.b

  // Read Merging
  private val arsel      = PickOneLow(arvld)
  private val slvHitVec  = Wire(Vec(buffer, Bool()))
  private val nidCalcVec = Wire(Vec(buffer, Bool()))
  private val rawNid     = PopCount(nidCalcVec)
  private val cncrtWkVld = io.slv.r.fire && io.mst.ar.fire && io.slv.r.bits._last && io.mst.ar.bits.id === io.slv.r.bits.id
  private val cncrtWkVldReg = RegNext(cncrtWkVld)
  private val cncrtWkEtrReg = RegEnable(arsel.bits, cncrtWkVld)

  io.slv.ar.valid := io.mst.ar.valid && arsel.valid
  io.slv.ar.bits := io.mst.ar.bits
  io.mst.ar.ready := io.slv.ar.ready && arsel.valid

  for(i <- arvld.indices) noPrefix {
    val rFireSlvHit = WireInit(io.slv.r.fire && io.slv.r.bits.id === arinfo(i).id && rvld(i))
    rFireSlvHit.suggestName(s"r_fire_slv_hit_$i")
    val arFireHit   = WireInit(io.mst.ar.fire && arsel.bits(i))
    arFireHit.suggestName(s"ar_fire_hit_$i")

    when(arFireHit) {
      arvld(i) := true.B
    }.elsewhen(rlsq.io.deq.fire && rlsq.io.deq.bits === i.U) {
      arvld(i) := false.B
      assert(arinfo(i).nid === 0.U)
    }

    when(arFireHit) {
      rvld(i)  := true.B
    }.elsewhen(rFireSlvHit && io.slv.r.bits._last && arinfo(i).nid === 0.U) {
      rvld(i)  := false.B
      assert(arvld(i), s"rvld but arvld is false in vec $i")
    }

    when(arFireHit) {
      arinfo(i).size := io.mst.ar.bits.size
      arinfo(i).id   := io.mst.ar.bits.id
    }
    when(arFireHit) {
      arinfo(i).nid  := rawNid
    }.elsewhen((arinfo(i).nid =/= 0.U && rFireSlvHit && io.slv.r.bits._last) && (cncrtWkEtrReg(i) && cncrtWkVldReg)) {
      arinfo(i).nid  := arinfo(i).nid - 2.U
    }.elsewhen((arinfo(i).nid =/= 0.U && rFireSlvHit && io.slv.r.bits._last) || (cncrtWkEtrReg(i) && cncrtWkVldReg)) {
      arinfo(i).nid  := arinfo(i).nid - 1.U
    }


    nidCalcVec(i)  := rvld(i) && arinfo(i).id === io.mst.ar.bits.id
    slvHitVec(i)   := rvld(i) && arinfo(i).id === io.slv.r.bits.id && arinfo(i).nid === 0.U
  }

  private val oriArLen = io.mst.ar.bits.len +& 1.U
  private val rlenShift = io.mst.ar.bits.size - maxSlvSize.U

  when(io.mst.ar.bits.size > maxSlvSize.U) {
    io.slv.ar.bits.size := maxSlvSize.U
    io.slv.ar.bits.len := (oriArLen << rlenShift).asUInt - 1.U
  }

  private val rwa = OHToUInt(slvHitVec)
  private val rwd = VecInit(Seq.fill(seg)(io.slv.r.bits.data))
  private val rwm = mrgMskVec(rwa)
  
  for(i <- mrgMskVec.indices) {
    for(j <- mrgMskVec(i).indices) {
      when(io.mst.ar.fire && arsel.bits(i)) {
        mrgMskVec(i)(j) := io.mst.ar.bits.addr(log2Ceil(mdw / 8) - 1, log2Ceil(sdw / 8)) === j.U
      }.elsewhen(io.slv.r.fire && rwa === i.U) {
        mrgMskVec(i)(j) := mrgMskVec(i)((j + seg - 1) % seg)
      }
    }
    when(arvld(i)) {
      assert(PopCount(mrgMskVec(i)) === 1.U, s"Merge mask of entry $i is illegal!")
    }
  }

  when(io.slv.r.fire) {
    rmem.write(rwa, rwd, rwm)
    assert(PopCount(slvHitVec) === 1.U, p"None or more than one hit, slvHitOH is ${Binary(slvHitVec.asUInt)}")
  }

  private val rid       = RegEnable(io.slv.r.bits.id, io.slv.r.fire)
  private val rlast     = RegEnable(io.slv.r.bits._last, io.slv.r.fire)
  private val rresp     = RegEnable(io.slv.r.bits.resp, io.slv.r.fire)
  private val ruser     = RegEnable(io.slv.r.bits.user, io.slv.r.fire)
  private val mergeDone = RegEnable(mrgMskVec(rwa).last && (arinfo(rwa).size > maxSlvSize.U), io.slv.r.fire)
  private val noMrgFire = RegEnable(arinfo(rwa).size <= maxSlvSize.U, io.slv.r.fire)

  io.mst.r.bits.id := rid
  io.mst.r.bits.data := rmem(rq.io.deq.bits(log2Ceil(buffer) - 1, 0)).asUInt
  io.mst.r.bits.resp := rresp
  io.mst.r.bits.user := ruser
  io.mst.r.bits.last := rlast

  io.mst.r.valid := rq.io.deq.valid && (mergeDone || rlast || noMrgFire)
  rq.io.deq.ready := rq.io.deq.valid && Mux(io.mst.r.ready, true.B, !mergeDone && !rlast && !noMrgFire)
  rq.io.enq.valid := io.slv.r.fire
  rq.io.enq.bits := rwa
  rlsq.io.enq.valid := io.slv.r.fire && io.slv.r.bits._last
  rlsq.io.enq.bits  := OHToUInt(slvHitVec)
  rlsq.io.deq.ready := io.mst.r.bits._last && io.mst.r.fire
  io.slv.r.ready := rq.io.enq.ready && rlsq.io.enq.ready
}
