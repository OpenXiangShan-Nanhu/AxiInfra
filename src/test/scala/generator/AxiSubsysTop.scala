// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.
package generator

import chisel3._
import chisel3.experimental.noPrefix
import org.chipsalliance.cde.config.Parameters
import xs.infra.axi._
import org.chipsalliance.cde.config.Field

case object AxiSubsysParamsKey extends Field[AxiSubsysParams]

case class AxiSubsysParams(
  slvp:Seq[PortParams] = Seq(),
  mstp:Seq[PortParams] = Seq(),
  memp:Seq[PortParams] = Seq(),
  internalDataBits:Int = 256
)

class AxiSubsysXbar(mstParams:Seq[AxiParams], slvMatchers:Seq[UInt => Bool], memParams: Seq[PortParams]) extends BaseAxiXbar(mstParams, memParams) {
  override val slvMatchersSeq = slvMatchers
  require(slvMatchersSeq.size == memParams.size)
  initialize()
}

class AxiSubsysTop(implicit p:Parameters) extends RawModule with ImplicitClock with ImplicitReset {
  private val subsysP = p(AxiSubsysParamsKey)
  val clock = IO(Input(Clock())).suggestName("clock")
  val reset = IO(Input(AsyncReset())).suggestName("reset")

  override val implicitClock = clock
  override val implicitReset = reset
  require(subsysP.slvp.nonEmpty, "slave ports should not be empty!")
  require((subsysP.mstp ++ subsysP.memp).nonEmpty, "master and memory ports should not be empty!")

  val slvs = for((sp, i) <- subsysP.slvp.zipWithIndex) yield noPrefix {
    val sfx = if(sp.name != "") sp.name else s"$i"
    val s_axi = IO(Flipped(new ExtAxiBundle(sp.axip)))
    val s_clk = sp.async.map(_ => IO(Input(Clock())))
    val s_rst = sp.async.map(_ => IO(Input(AsyncReset())))
    val adpt = Module(new AxiInPortAdapter(sp, subsysP.internalDataBits))
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

  private val xbar = Module(new AxiSubsysXbar(slvs.map(_._1.params), (subsysP.mstp ++ subsysP.memp).map(_.addr.test), (subsysP.mstp ++ subsysP.memp)))
  xbar.io.upstream.zip(slvs.map(_._1)).foreach({ case(a, b) => a <> b })

  private val mstPs = xbar.io.downstream.take(subsysP.mstp.size)
  private val memPs =  xbar.io.downstream.drop(subsysP.mstp.size)

  private val mems = for(((pp, mp), i) <- subsysP.memp.zip(memPs).zipWithIndex) yield noPrefix {
    val sfx = if(pp.name != "") pp.name else s"$i"
    val mem_axi = IO(new ExtAxiBundle(mp.params))
    mem_axi.suggestName(s"mem_$sfx")
    mp <> mem_axi
  }

  val msts = for(((pp, a), i) <- subsysP.mstp.zip(mstPs).zipWithIndex) yield noPrefix {
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
