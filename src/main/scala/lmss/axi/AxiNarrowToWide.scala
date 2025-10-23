package lmss.axi

import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import chisel3.experimental.noPrefix

class AxiWidthWCvtBundle(axiP: AxiParams) extends Bundle {
  val addr = UInt(axiP.addrBits.W)
  val size = UInt(axiP.sizeBits.W)
  val id = UInt(axiP.idBits.W)

  def := (in: AWFlit): Unit = {
    this.addr := in.addr
    this.size := in.size
    this.id   := in.id
  }
  def := (in: ARFlit): Unit = {
    this.addr := in.addr
    this.size := in.size
    this.id   := in.id
  }
}

class AxiWidthRCvtBundle(axiP: AxiParams, outstanding: Int) extends Bundle {
  val addr = UInt(axiP.addrBits.W)
  val size = UInt(axiP.sizeBits.W)
  val id = UInt(axiP.idBits.W)
  val nid = UInt(log2Ceil(outstanding).W)
}

class AxiNarrowToWide(mstParams: AxiParams, slvParams: AxiParams, buffer:Int) extends Module {
  override val desiredName = s"AxiWidthCvt${mstParams.dataBits}To${slvParams.dataBits}"
  val io = IO(new Bundle {
    val mst = Flipped(new AxiBundle(mstParams))
    val slv = new AxiBundle(slvParams)
  })
  private val mdw = mstParams.dataBits
  private val sdw = slvParams.dataBits
  private val seg = sdw / mdw
  private val awq = Module(new Queue(new AxiWidthWCvtBundle(mstParams), entries = buffer))
  private val wq = Module(new Queue(new WFlit(mstParams), entries = 2))
  private val rq = Module(new Queue(new RFlit(slvParams), entries = 1, pipe = true))
  private val arvld = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val arinfo = Reg(Vec(buffer, new AxiWidthRCvtBundle(mstParams, buffer)))
  private val arsel = PickOneLow(arvld)
  private val infoSelOH = Wire(Vec(buffer, Bool()))
  private val nidCalcVec = Wire(Vec(buffer, Bool()))
  private val rawNid = PopCount(nidCalcVec)
  private val cncrtWkVld = io.mst.r.fire && io.mst.ar.fire && io.mst.r.bits._last && io.mst.r.bits.id === io.mst.ar.bits.id
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
    when(io.mst.ar.fire && arsel.bits(i)) {
      arinfo(i) := io.mst.ar.bits
    }
    when(arFireHit) {
      arinfo(i).size  := io.mst.ar.bits.size
      arinfo(i).id    := io.mst.ar.bits.id
    }
    when(arFireHit) {
      arinfo(i).nid   := rawNid
    }.elsewhen((cncrtWkVldReg && cncrtWkEtrReg(i)) && (rFireMayHit && io.mst.r.bits._last && arinfo(i).nid =/= 0.U)) {
      arinfo(i).nid   := arinfo(i).nid - 2.U
    }.elsewhen((cncrtWkVldReg && cncrtWkEtrReg(i)) || (rFireMayHit && io.mst.r.bits._last && arinfo(i).nid =/= 0.U)) {
      arinfo(i).nid   := arinfo(i).nid - 1.U
    }
    when(arFireHit) {
      arinfo(i).addr := io.mst.ar.bits.addr
    }.elsewhen(rFireMayHit && arinfo(i).nid === 0.U) {
      arinfo(i).addr := arinfo(i).addr + (i.U << arinfo(i).size)
    }

    infoSelOH(i)     := arvld(i) && arinfo(i).id === io.mst.r.bits.id && arinfo(i).nid === 0.U
    nidCalcVec(i)    := arvld(i) && arinfo(i).id === io.mst.ar.bits.id
  }
  //AW Channel Connection
  awq.io.enq.valid := io.mst.aw.valid && io.slv.aw.ready
  awq.io.enq.bits := io.mst.aw.bits
  io.slv.aw.valid := io.mst.aw.valid && awq.io.enq.ready
  io.slv.aw.bits := io.mst.aw.bits
  io.mst.aw.ready := io.slv.aw.ready && awq.io.enq.ready

  //AR Channel Connection
  io.slv.ar.valid := io.mst.ar.valid && arsel.valid
  io.slv.ar.bits := io.mst.ar.bits
  io.mst.ar.ready := io.slv.ar.ready && arsel.valid

  //W Channel Connection
  private val strb = Wire(Vec(seg, UInt((mdw / 8).W)))
  private val waddrcvt = if(sdw > mdw) awq.io.deq.bits.addr(log2Ceil(sdw / 8) - 1, log2Ceil(mdw / 8)) else 0.U
  strb.zipWithIndex.foreach({case(s, i) => s := Mux(waddrcvt === i.U, wq.io.deq.bits.strb, 0.U)})

  wq.io.enq <> io.mst.w
  io.slv.w.valid := wq.io.deq.valid && awq.io.deq.valid
  io.slv.w.bits := wq.io.deq.bits
  io.slv.w.bits.data := Fill(seg, wq.io.deq.bits.data)
  io.slv.w.bits.strb := strb.asUInt
  wq.io.deq.ready := io.slv.w.ready && awq.io.deq.valid
  awq.io.deq.ready := io.slv.w.ready && wq.io.deq.valid && wq.io.deq.bits._last

  //B Channel Connection
  io.mst.b <> io.slv.b

  //R Channel Connection
  private val infoSel = arinfo(io.slv.r.bits.id(log2Ceil(buffer) - 1, 0))
  private val raddrcvt = if(sdw > mdw) infoSel.addr(log2Ceil(sdw / 8) - 1, log2Ceil(mdw / 8)) else 0.U
  private val rdata = io.slv.r.bits.data.asTypeOf(Vec(seg, UInt(mdw.W)))

  rq.io.enq <> io.slv.r
  io.mst.r  <> rq.io.deq
  io.mst.r.bits.data := rdata(raddrcvt)

  when(io.mst.r.fire) {
    assert(PopCount(infoSelOH) === 1.U, s"Multiple R entries are hit!")
  }
}