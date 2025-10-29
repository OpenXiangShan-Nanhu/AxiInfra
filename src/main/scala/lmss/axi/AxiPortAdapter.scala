package lmss.axi

import chisel3._
import chisel3.experimental.noPrefix
import lmss.param.{LmssParamsKey, PortParams}
import org.chipsalliance.cde.config.Parameters

class AxiInPortAdapter(port:PortParams)(implicit p:Parameters) extends RawModule {
  private val inP = port.axip
  private val outP = port.axip.copy(dataBits = p(LmssParamsKey).internalDataBits)

  val s_axi = IO(Flipped(new ExtAxiBundle(inP)))
  val s_clk = IO(Input(Clock()))
  val s_rst = IO(Input(AsyncReset()))

  val m_axi = IO(new ExtAxiBundle(outP))
  val m_clk = IO(Input(Clock()))
  val m_rst = IO(Input(AsyncReset()))

  private val cdc = port.async.isDefined
  private val pipe = withClockAndReset(s_clk, s_rst) { Module(new AxiBufferChain(inP, port.pipe)) }
  pipe.io.in <> s_axi

  if(port.axip.dataBits > p(LmssParamsKey).internalDataBits) noPrefix {
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
  } else if(port.axip.dataBits < p(LmssParamsKey).internalDataBits) noPrefix {
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(inP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(inP, port.async.get))) }
    val reorder = withClockAndReset(m_clk, m_rst) { Module(new AxiReorder(inP, port.outstanding * 2))}
    val cvt = withClockAndReset(m_clk, m_rst) { Module(new AxiNarrowToWide(inP, outP, port.outstanding)) }
    cvt.suggestName("cvt")
    reorder.suggestName("reorder")
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    if(cdc) {
      asyncSrc.get.s_axi <> s_axi
      asycnSink.get.async <> asyncSrc.get.async
      reorder.io.mst <> asycnSink.get.m_axi
      cvt.io.mst <> reorder.io.slv
      m_axi <> cvt.io.slv
    } else {
      reorder.io.mst <> s_axi
      cvt.io.mst <> reorder.io.slv
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

class AxiOutPortAdapter(axiP:AxiParams, port:PortParams) extends RawModule {
  private val inP = axiP
  private val outP = axiP.copy(dataBits = port.axip.dataBits)

  val s_axi = IO(Flipped(new ExtAxiBundle(inP)))
  val s_clk = IO(Input(Clock()))
  val s_rst = IO(Input(AsyncReset()))

  val m_axi = IO(new ExtAxiBundle(outP))
  val m_clk = IO(Input(Clock()))
  val m_rst = IO(Input(AsyncReset()))

  private val cdc = port.async.isDefined
  private val pipe = withClockAndReset(m_clk, m_rst) { Module(new AxiBufferChain(outP, port.pipe)) }
  m_axi <> pipe.io.out

  if(axiP.dataBits > port.axip.dataBits) noPrefix {
    val cvt = withClockAndReset(s_clk, s_rst) { Module(new AxiWideToNarrow(inP, outP, port.outstanding)) }
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(outP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(outP, port.async.get))) }
    cvt.suggestName("cvt")
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    cvt.io.mst <> s_axi
    if(cdc) {
      asyncSrc.get.s_axi <> cvt.io.slv
      asycnSink.get.async <> asyncSrc.get.async
      pipe.io.in <> asycnSink.get.m_axi
    } else {
      pipe.io.in <> cvt.io.slv
    }
  } else if(axiP.dataBits < port.axip.dataBits) noPrefix {
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(inP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(inP, port.async.get))) }
    val cvt = withClockAndReset(m_clk, m_rst) { Module(new AxiNarrowToWide(inP, outP, port.outstanding)) }
    cvt.suggestName("cvt")
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    if(cdc) {
      asyncSrc.get.s_axi <> s_axi
      asycnSink.get.async <> asyncSrc.get.async
      cvt.io.mst <> asycnSink.get.m_axi
      pipe.io.in <> cvt.io.slv
    } else {
      cvt.io.mst <> s_axi
      pipe.io.in <> cvt.io.slv
    }
  } else {
    val asyncSrc = withClockAndReset(s_clk, s_rst) { Option.when(cdc)(Module(new AxiAsyncSource(inP, port.async.get))) }
    val asycnSink = withClockAndReset(m_clk, m_rst) { Option.when(cdc)(Module(new AxiAsyncSink(inP, port.async.get))) }
    asyncSrc.map(_.suggestName("async_src"))
    asycnSink.map(_.suggestName("async_sink"))
    if(cdc) {
      asyncSrc.get.s_axi <> s_axi
      asycnSink.get.async <> asyncSrc.get.async
      pipe.io.in <> asycnSink.get.m_axi
    } else {
      pipe.io.in <> s_axi
    }
  }
}