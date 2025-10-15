package lmss

import chisel3._
import chisel3.experimental.noPrefix
import lmss.axi._
import lmss.mem.MemoryBank
import lmss.param.LmssParamsKey
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule

class LaomaXbar(mstParams:Seq[AxiParams], slvMatchers:Seq[UInt => Bool]) extends BaseAxiXbar(mstParams) {
  override val slvMatchersSeq = slvMatchers
  initialize()
}

class LaomaSubsys(implicit p:Parameters) extends RawModule with ImplicitClock with ImplicitReset {
  private val lmssP = p(LmssParamsKey)
  val clock = IO(Input(Clock()))
  val reset = IO(Input(AsyncReset()))

  override val implicitClock = clock
  override val implicitReset = reset
  require(lmssP.slvp.nonEmpty, "slave ports should not be empty!")
  require((lmssP.mstp ++ lmssP.memp).nonEmpty, "master and memory ports should not be empty!")

  val slvs = for((sp, i) <- lmssP.slvp.zipWithIndex) yield noPrefix {
    val sfx = if(sp.name != "") sp.name else s"$i"
    val s_axi = IO(Flipped(new ExtAxiBundle(sp.axip)))
    val s_clk = sp.async.map(_ => IO(Input(Clock())))
    val s_rst = sp.async.map(_ => IO(Input(AsyncReset())))
    val adpt = Module(new AxiInPortAdapter(sp))
    adpt.s_clk := s_clk.getOrElse(clock)
    adpt.s_rst := s_rst.getOrElse(reset)
    adpt.m_clk := clock
    adpt.m_rst := reset
    adpt.s_axi <> s_axi
    s_axi.suggestName(s"s_axi_$sfx")
    s_clk.foreach(_.suggestName(s"s_aclk_$sfx"))
    s_rst.foreach(_.suggestName(s"s_arst_$sfx"))
    (adpt.m_axi, s_axi, s_clk, s_rst)
  }

  private val xbar = Module(new LaomaXbar(slvs.map(_._1.params), (lmssP.mstp ++ lmssP.memp).map(_.addr.test)))
  xbar.io.upstream.zip(slvs.map(_._1)).foreach({ case(a, b) => a <> b })

  private val mstPs = xbar.io.downstream.take(lmssP.mstp.size)
  private val memPs =  xbar.io.downstream.drop(lmssP.mstp.size)

  private val mems = for(((pp, mp), i) <- lmssP.memp.zip(memPs).zipWithIndex) yield noPrefix {
    val sfx = if(pp.name != "") pp.name else s"$i"
    val memBank = LazyModule(new MemoryBank(mp.params))
    val mb = Module(memBank.module)
    mb.s_axi <> mp
    memBank.suggestName(s"mem_$sfx")
    mb
  }

  val msts = for(((pp, a), i) <- lmssP.mstp.zip(mstPs).zipWithIndex) yield noPrefix {
    val sfx = if(pp.name != "") pp.name else s"$i"
    val adpt = Module(new AxiOutPortAdapter(a.params, pp))
    val m_axi = IO(new ExtAxiBundle(adpt.m_axi.params))
    val m_clk = pp.async.map(_ => IO(Input(Clock())))
    val m_rst = pp.async.map(_ => IO(Input(AsyncReset())))
    adpt.s_clk := clock
    adpt.s_rst := reset
    adpt.m_clk := m_clk.getOrElse(clock)
    adpt.m_rst := m_rst.getOrElse(reset)
    adpt.s_axi <> a
    m_axi <> adpt.m_axi
    m_axi.suggestName(s"m_axi_$sfx")
    m_clk.foreach(_.suggestName(s"m_aclk_$sfx"))
    m_rst.foreach(_.suggestName(s"m_arst_$sfx"))
    (m_axi, m_clk, m_rst)
  }
}
