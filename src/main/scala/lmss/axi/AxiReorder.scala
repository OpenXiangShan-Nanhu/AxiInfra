package lmss.axi

    
import chisel3._
import chisel3.util._
import xs.utils.PickOneLow
import chisel3.experimental.noPrefix
import freechips.rocketchip.diplomacy.BufferParams.pipe

class AxiARInfoBundle(axiP: AxiParams, buffer: Int) extends Bundle {
  val bits        = new AxiBundle(axiP).ar.bits
  val nid         = UInt(log2Ceil(buffer).W)
  val haveSendAR  = Bool()
}
class AxiAWInfoBundle(axiP: AxiParams, buffer: Int) extends Bundle {
  val id         = UInt(log2Ceil(axiP.idBits).W)
  val nid        = UInt(log2Ceil(buffer).W)
  val haveSendAW = Bool()
}

class AxiWEtrBundle(axiP: AxiParams, buffer: Int) extends Bundle {
  val winfo      = new AxiBundle(axiP).w.bits
  val entry      = UInt(log2Ceil(buffer).W)
}
class AxiAWEtrBundle(axiP: AxiParams, buffer: Int) extends Bundle {
  val awinfo     = new AxiBundle(axiP).aw.bits
  val entry      = UInt(log2Ceil(buffer).W)
}

class AxiReorder(axiParams: AxiParams, buffer:Int) extends Module {
  override val desiredName = "AxiRecoder"
  val io = IO(new Bundle {
    val mst = Flipped(new AxiBundle(axiParams))
    val slv = new AxiBundle(axiParams)
  })

  private val rvld   = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val wvld   = RegInit(VecInit(Seq.fill(buffer)(false.B)))
  private val arinfo = Reg(Vec(buffer, new AxiARInfoBundle(axiParams, buffer)))
  private val awinfo = Reg(Vec(buffer, new AxiAWInfoBundle(axiParams, buffer)))

  private val arsel  = PickOneLow(rvld)
  private val awsel  = PickOneLow(wvld)

  private val slvRHitEtr    = io.slv.r.bits.id(log2Ceil(buffer) - 1, 0)
  private val slvBHitEtr    = io.slv.b.bits.id(log2Ceil(buffer) - 1, 0)
  private val slvARHitEtr   = io.slv.ar.bits.id(log2Ceil(buffer) - 1, 0)
  private val slvAWHitEtr   = io.slv.aw.bits.id(log2Ceil(buffer) - 1, 0)

  private val nidRCalcVec   = Wire(Vec(buffer, Bool()))
  private val nidWCalcVec   = Wire(Vec(buffer, Bool()))
  private val rawRNid       = PopCount(nidRCalcVec)
  private val rawWNid       = PopCount(nidWCalcVec)
  private val rWkVld        = io.slv.r.fire && io.mst.ar.fire && io.slv.r.bits._last && io.mst.ar.bits.id === arinfo(slvRHitEtr).bits.id
  private val rWkVldReg     = RegNext(rWkVld)
  private val rWkEtrReg     = RegEnable(arsel.bits, rWkVld)
  private val wWkVld        = io.slv.b.fire && io.mst.aw.fire && io.mst.aw.bits.id === awinfo(slvBHitEtr).id
  private val wWkVldReg     = RegNext(wWkVld)
  private val wWkEtrReg     = RegEnable(awsel.bits, wWkVld)
  private val awq           = Module(new Queue(new AxiAWEtrBundle(axiParams, buffer), entries = 1, pipe = true))
  private val wq            = Module(new Queue(UInt(log2Ceil(buffer).W), entries = 2, pipe = true))
  private val wbitsq        = Module(new Queue(new AxiWEtrBundle(axiParams, buffer), entries = 2, pipe = false))

  private val arShouldSend  = Wire(Vec(buffer, Bool()))


  for(i <- rvld.indices) noPrefix {
    val arMstFireHit = WireInit(io.mst.ar.fire && arsel.bits(i))
    arMstFireHit.suggestName(s"ar_mst_fire_hit_$i")
    val rFireSlvHit = WireInit(io.slv.r.fire && arinfo(slvRHitEtr).bits.id === arinfo(i).bits.id && rvld(i))
    rFireSlvHit.suggestName(s"r_fire_slv_hit_$i")
    val arSlvFireHit = WireInit(io.slv.ar.fire && slvARHitEtr === i.U)
    arSlvFireHit.suggestName(s"ar_slv_fire_hit_$i")

    when(arMstFireHit) {
      rvld(i)  := true.B
    }.elsewhen(io.slv.r.fire && io.slv.r.bits._last && slvRHitEtr === i.U) {
      assert(rvld(i))
      rvld(i)  := false.B
      assert(arinfo(i).nid === 0.U)
    }

    when(arMstFireHit) {
      arinfo(i).bits       := io.mst.ar.bits
      arinfo(i).haveSendAR := false.B
    }
    when(arMstFireHit) {
      arinfo(i).nid  := rawRNid
    }.elsewhen((arinfo(i).nid =/= 0.U && rFireSlvHit && io.slv.r.bits._last) && (rWkVldReg && rWkEtrReg(i))) {
      assert(arinfo(i).nid =/= 1.U && arinfo(i).nid =/= 0.U)
      arinfo(i).nid  := arinfo(i).nid - 2.U
    }.elsewhen((arinfo(i).nid =/= 0.U && rFireSlvHit && io.slv.r.bits._last) || (rWkVldReg && rWkEtrReg(i))) {
      assert(arinfo(i).nid =/= 0.U)
      arinfo(i).nid  := arinfo(i).nid - 1.U
    }
    when(arSlvFireHit) {
      arinfo(i).haveSendAR := true.B
    }
    nidRCalcVec(i)   := rvld(i) && arinfo(i).bits.id === io.mst.ar.bits.id
    arShouldSend(i)  := rvld(i) && arinfo(i).nid === 0.U && !arinfo(i).haveSendAR
  }

  for(i <- wvld.indices) noPrefix {
    val awMstFireHit   = WireInit(io.mst.aw.fire && awsel.bits(i))
    awMstFireHit.suggestName(s"aw_mst_fire_hit_$i")
    val bFireSlvHit    = WireInit(io.slv.b.fire && arinfo(slvRHitEtr).bits.id === arinfo(i).bits.id && wvld(i))
    bFireSlvHit.suggestName(s"b_fire_slv_hit_$i")
    val awSlvFireHit   = WireInit(io.slv.aw.fire && slvAWHitEtr === i.U)
    awSlvFireHit.suggestName(s"aw_slv_fire_hit_$i")

    when(awMstFireHit) {
      wvld(i)   := true.B
    }.elsewhen(io.slv.b.fire && slvBHitEtr === i.U) {
      assert(wvld(i), s"B fire but vec($i) is not valid")
      assert(awinfo(i).nid === 0.U)
      wvld(i)   := false.B
    }
    when(awMstFireHit) {
      awinfo(i).nid  := rawWNid
      awinfo(i).id   := io.mst.aw.bits.id
    }.elsewhen((awinfo(i).nid =/= 0.U && bFireSlvHit) && (wWkVldReg && wWkEtrReg(i))) {
      awinfo(i).nid  := awinfo(i).nid - 2.U
    }.elsewhen((awinfo(i).nid =/= 0.U && bFireSlvHit) || (wWkVldReg && wWkEtrReg(i))) {
      awinfo(i).nid  := awinfo(i).nid - 1.U
    }
    when(awMstFireHit) {
      awinfo(i).haveSendAW := false.B
    }.elsewhen(awq.io.deq.fire && awq.io.deq.bits.entry === i.U) {
      awinfo(i).haveSendAW := true.B
    }

    nidWCalcVec(i)  := wvld(i) && awinfo(i).id === io.mst.aw.bits.id
  }

  private val selSendAR   = PriorityEncoder(arShouldSend)
  private val wMstHitEtr  = wq.io.deq.bits
  
  wq.io.enq.valid        := io.mst.aw.fire
  wq.io.enq.bits         := OHToUInt(awsel.bits)
  wq.io.deq.ready        := io.mst.w.fire && io.mst.w.bits._last

  awq.io.enq.valid       := io.mst.aw.valid && awsel.valid && wq.io.enq.ready
  awq.io.enq.bits.entry  := OHToUInt(awsel.bits)
  awq.io.enq.bits.awinfo := io.mst.aw.bits
  awq.io.deq.ready       := awinfo(awq.io.deq.bits.entry).nid === 0.U && io.slv.aw.ready

  wbitsq.io.enq.bits.winfo := io.mst.w.bits
  wbitsq.io.enq.bits.entry := wq.io.deq.bits
  wbitsq.io.enq.valid      := io.mst.w.fire
  wbitsq.io.deq.ready      := awinfo(wbitsq.io.deq.bits.entry).haveSendAW && io.slv.w.ready

  io.mst.ar.ready        := arsel.valid
  io.mst.aw.ready        := awsel.valid && wq.io.enq.ready && awq.io.enq.ready
  io.mst.r.bits          := io.slv.r.bits
  io.mst.r.bits.id       := arinfo(slvRHitEtr).bits.id
  io.mst.r.valid         := io.slv.r.valid
  io.mst.b.valid         := io.slv.b.valid
  io.mst.b.bits          := io.slv.b.bits
  io.mst.b.bits.id       := awinfo(slvBHitEtr).id
  io.mst.w.ready         := wbitsq.io.enq.ready && wq.io.deq.valid
  io.slv.ar.bits         := arinfo(selSendAR).bits
  io.slv.ar.bits.id      := selSendAR
  io.slv.ar.valid        := arShouldSend.reduce(_ | _)
  io.slv.aw.bits         := awq.io.deq.bits.awinfo
  io.slv.aw.bits.id      := awq.io.deq.bits.entry
  io.slv.aw.valid        := awinfo(awq.io.deq.bits.entry).nid === 0.U && awq.io.deq.valid
  io.slv.w.valid         := wbitsq.io.deq.valid && awinfo(wbitsq.io.deq.bits.entry).haveSendAW
  io.slv.w.bits          := wbitsq.io.deq.bits.winfo
  io.slv.r.ready         := io.mst.r.ready
  io.slv.b.ready         := io.mst.b.ready



  //assertion
  when(io.mst.w.fire) {
    assert(wq.io.deq.valid, "data should not in this module")
  }
  when(io.slv.r.fire) {
    assert(rvld(slvRHitEtr), "r fire but not hit")
  }
}

