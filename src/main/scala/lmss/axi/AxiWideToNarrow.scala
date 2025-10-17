package lmss.axi

import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import xs.utils.queue.MimoQueue

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
  private val awinfo = Reg(new AxiWidthCvtBundle(mstParams))
  private val wq = Module(new MimoQueue(new WFlit(slvParams), seg, 1, buffer * seg, false))

  private val arvld = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val arinfo = Reg(Vec(buffer, new AxiWidthCvtBundle(mstParams)))
  private val mrgMskVec = Reg(Vec(buffer, Vec(seg, Bool())))
  private val rmem = Mem(buffer, Vec(seg, UInt(sdw.W)))
  private val rq = Module(new Queue(UInt(slvParams.idBits.W), 1, pipe = true))


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

  private val lenShift = io.mst.aw.bits.size - maxSlvSize.U
  private val oriAwLen = io.mst.aw.bits.len +& 1.U
  when(io.mst.aw.bits.size > maxSlvSize.U) {
    io.slv.aw.bits.size := maxSlvSize.U
    io.slv.aw.bits.len := (oriAwLen << lenShift).asUInt - 1.U
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
  private val arsel = PickOneLow(arvld)
  io.slv.ar.valid := io.mst.ar.valid && arsel.valid
  io.slv.ar.bits := io.mst.ar.bits
  io.slv.ar.bits.id := OHToUInt(arsel.bits)
  io.mst.ar.ready := io.slv.ar.ready && arsel.valid

  for(i <- arvld.indices) {
    when(io.mst.ar.fire && arsel.bits(i)) {
      arvld(i) := true.B
    }.elsewhen(io.mst.r.fire && io.mst.r.bits.id === arinfo(i).id && io.mst.r.bits._last && arvld(i)) {
      arvld(i) := false.B
    }
    when(io.mst.ar.fire && arsel.bits(i)) {
      arinfo(i) := io.mst.ar.bits
    }
  }

  private val oriArLen = io.mst.aw.bits.len +& 1.U
  when(io.mst.ar.bits.size > maxSlvSize.U) {
    io.slv.ar.bits.size := maxSlvSize.U
    io.slv.ar.bits.len := (oriArLen << lenShift).asUInt - 1.U
  }

  private val rwa = io.slv.r.bits.id(log2Ceil(buffer) - 1, 0)
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
  }

  private val rid = RegEnable(arinfo(rwa).id, io.slv.r.fire)
  private val rlast = RegEnable(io.slv.r.bits._last, io.slv.r.fire)
  private val rresp = RegEnable(io.slv.r.bits.resp, io.slv.r.fire)
  private val ruser = RegEnable(io.slv.r.bits.user, io.slv.r.fire)
  private val mergeDone = mrgMskVec(rwa).last

  io.mst.r.bits.id := rid
  io.mst.r.bits.data := rmem(rq.io.deq.bits(log2Ceil(buffer) - 1, 0)).asUInt
  io.mst.r.bits.resp := rresp
  io.mst.r.bits.user := ruser
  io.mst.r.bits.last := rlast

  io.mst.r.valid := rq.io.deq.valid && (mergeDone || rlast)
  rq.io.deq.ready := rq.io.deq.valid && Mux(io.mst.r.ready, true.B, !mergeDone && !rlast)
  rq.io.enq.valid := io.slv.r.valid
  rq.io.enq.bits := io.slv.r.bits.id
  io.slv.r.ready := rq.io.enq.ready
}
