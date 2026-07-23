// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi

import chisel3._
import chisel3.experimental.noPrefix
import org.chipsalliance.cde.config.Parameters

class AxiInPortAdapter(port:PortParams, outDataBits:Int)(implicit p:Parameters) extends RawModule {
  private val inP = port.axip
  private val outP = port.axip.copy(dataBits = outDataBits)

  val s_axi = IO(Flipped(new ExtAxiBundle(inP)))
  val s_clk = IO(Input(Clock()))
  val s_rst = IO(Input(AsyncReset()))

  val m_axi = IO(new ExtAxiBundle(outP))
  val m_clk = IO(Input(Clock()))
  val m_rst = IO(Input(AsyncReset()))

  private val cdc = port.async.isDefined
  private val pipe = withClockAndReset(s_clk, s_rst) { Module(new AxiBufferChain(inP, port.pipe)) }
  pipe.io.in <> s_axi

  if(port.axip.dataBits > outDataBits) noPrefix {
    val cvt = withClockAndReset(s_clk, s_rst) { Module(new AxiWideToNarrow(inP, outP, port.outstanding)) }
    val reorder = withClockAndReset(m_clk, m_rst) { Module(new AxiReorder(inP, port.outstanding * 2))}
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(outP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(outP, port.async.get))) }
    cvt.suggestName("cvt")
    reorder.suggestName("reorder")
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    reorder.io.mst <> pipe.io.out
    cvt.io.mst <> reorder.io.slv
    if(cdc) {
      asyncSrc.get.s_axi <> cvt.io.slv
      asycnSink.get.async <> asyncSrc.get.async
      m_axi <> asycnSink.get.m_axi
    } else {
      m_axi <> cvt.io.slv
    }
  } else if(port.axip.dataBits < outDataBits) noPrefix {
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(inP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(inP, port.async.get))) }
    val reorder = withClockAndReset(m_clk, m_rst) { Module(new AxiReorder(inP, port.outstanding * 2))}
    val cvt = withClockAndReset(m_clk, m_rst) { Module(new AxiNarrowToWide(inP, outP, port.outstanding)) }
    cvt.suggestName("cvt")
    reorder.suggestName("reorder")
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    reorder.io.mst <> pipe.io.out
    cvt.io.mst <> reorder.io.slv
    if(cdc) {
      asyncSrc.get.s_axi <> cvt.io.slv
      asycnSink.get.async <> asyncSrc.get.async
      m_axi <> asycnSink.get.m_axi
    } else {
      m_axi <> cvt.io.slv
    }
  } else {
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(inP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(inP, port.async.get))) }
    val reorder = withClockAndReset(m_clk, m_rst) { Module(new AxiReorder(inP, port.outstanding * 2))}
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    reorder.io.mst <> pipe.io.out
    if(cdc) {
      asyncSrc.get.s_axi <> reorder.io.slv
      asycnSink.get.async <> asyncSrc.get.async
      m_axi <> asycnSink.get.m_axi
    } else {
      m_axi <> reorder.io.slv
    }
  }
}

/**
  * Master-side port adapter: xbar (axiP) -> external slave (port.axip).
  *
  * Pipeline:
  *   s_axi
  *     -> [data width convert + optional CDC]  => mid_axi (midP)
  *     -> [optional AxiFieldAdapter]          => protocol fields of port.axip
  *     -> pipe buffer
  *     -> m_axi
  *
  * CDC is kept on the narrow side (W2N: convert then CDC; N2W: CDC then convert).
  */
class AxiOutPortAdapter(axiP: AxiParams, port: PortParams) extends RawModule {
  // Width converters require equal idBits; only dataBits may change on midP.
  // (buffer >= 2 avoids log2Ceil(1)=0 inside converters)
  private val cvtBuffer = math.max(port.outstanding, 2)
  private val midP = axiP.copy(dataBits = port.axip.dataBits)
  private val outP = port.axip

  val s_axi = IO(Flipped(new ExtAxiBundle(axiP)))
  val s_clk = IO(Input(Clock()))
  val s_rst = IO(Input(AsyncReset()))
  val m_axi = IO(new ExtAxiBundle(outP))
  val m_clk = IO(Input(Clock()))
  val m_rst = IO(Input(AsyncReset()))

  private val cdc = port.async.isDefined
  private val needFieldCvt =
    midP.idBits != outP.idBits ||
    midP.addrBits != outP.addrBits ||
    midP.lenBits != outP.lenBits ||
    midP.lockBits != outP.lockBits ||
    midP.qosBits != outP.qosBits ||
    midP.regionBits != outP.regionBits ||
    midP.sizeBits != outP.sizeBits ||
    midP.burstBits != outP.burstBits ||
    midP.cacheBits != outP.cacheBits ||
    midP.userBits != outP.userBits ||
    midP.lastBits != outP.lastBits

  private val pipe = withClockAndReset(m_clk, m_rst) { Module(new AxiBufferChain(outP, port.pipe)) }
  private val mid_axi = Wire(new AxiBundle(midP))
  m_axi <> pipe.io.out

  // ---- step 1: data width + optional CDC -> mid_axi ----
  if (axiP.dataBits > outP.dataBits) noPrefix {
    // wide -> narrow: convert on s_clk, then CDC (narrow side)
    val cvt = withClockAndReset(s_clk, s_rst) { Module(new AxiWideToNarrow(axiP, midP, cvtBuffer)) }
    cvt.suggestName("cvt")
    cvt.io.mst <> s_axi
    if (cdc) {
      val asyncSrc = withClockAndReset(s_clk, s_rst) { Module(new AxiAsyncSource(midP, port.async.get)) }
      val asyncSink = withClockAndReset(m_clk, m_rst) { Module(new AxiAsyncSink(midP, port.async.get)) }
      asyncSrc.suggestName("async_src")
      asyncSink.suggestName("async_sink")
      asyncSrc.s_axi <> cvt.io.slv
      asyncSink.async <> asyncSrc.async
      mid_axi <> asyncSink.m_axi
    } else {
      mid_axi <> cvt.io.slv
    }
  } else if (axiP.dataBits < outP.dataBits) noPrefix {
    // narrow -> wide: CDC on narrow side, then convert on m_clk
    val cvt = withClockAndReset(m_clk, m_rst) { Module(new AxiNarrowToWide(axiP, midP, cvtBuffer)) }
    cvt.suggestName("cvt")
    if (cdc) {
      val asyncSrc = withClockAndReset(s_clk, s_rst) { Module(new AxiAsyncSource(axiP, port.async.get)) }
      val asyncSink = withClockAndReset(m_clk, m_rst) { Module(new AxiAsyncSink(axiP, port.async.get)) }
      asyncSrc.suggestName("async_src")
      asyncSink.suggestName("async_sink")
      asyncSrc.s_axi <> s_axi
      asyncSink.async <> asyncSrc.async
      cvt.io.mst <> asyncSink.m_axi
    } else {
      cvt.io.mst <> s_axi
    }
    mid_axi <> cvt.io.slv
  } else {
    // same data width => midP fields match axiP; only optional CDC
    if (cdc) {
      val asyncSrc = withClockAndReset(s_clk, s_rst) { Module(new AxiAsyncSource(axiP, port.async.get)) }
      val asyncSink = withClockAndReset(m_clk, m_rst) { Module(new AxiAsyncSink(axiP, port.async.get)) }
      asyncSrc.suggestName("async_src")
      asyncSink.suggestName("async_sink")
      asyncSrc.s_axi <> s_axi
      asyncSink.async <> asyncSrc.async
      mid_axi <> asyncSink.m_axi
    } else {
      mid_axi <> s_axi
    }
  }

  // ---- step 2: protocol fields midP -> outP, then pipe ----
  if (needFieldCvt) noPrefix {
    val field = withClockAndReset(m_clk, m_rst) { Module(new AxiFieldAdapter(midP, outP, port.outstanding)) }
    field.suggestName("field")
    field.io.in <> mid_axi
    pipe.io.in <> field.io.out
  } else {
    pipe.io.in <> mid_axi
  }
}
