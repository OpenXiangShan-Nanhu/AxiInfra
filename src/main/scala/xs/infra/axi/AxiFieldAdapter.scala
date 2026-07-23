// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi

import chisel3._
import chisel3.util._
import chisel3.experimental.noPrefix
import xs.utils.PickOneLow

/**
  * Adapt non-data AXI fields between two parameterizations with equal data width.
  *
  * ID restore on B/R:
  *  - same idBits: pass-through
  *  - out idBits == 0 (dropId): FIFO Queue of original AWID/ARID.
  *    Downstream returns B/R in order of AW/AR, so head of queue is correct;
  *    outstanding may be > 1.
  *  - idBits differ (truncate/extend): scoreboard stores original ID;
  *    outbound uses resized ID; B/R match returned outId with nid ordering
  *    (same outId, earliest first) to restore original AWID/ARID.
  */
class AxiFieldAdapter(inP: AxiParams, outP: AxiParams, outstanding: Int = 32) extends Module {
  require(inP.dataBits == outP.dataBits, "AxiFieldAdapter requires equal dataBits")
  require(outstanding >= 1)

  val io = IO(new Bundle {
    val in  = Flipped(new AxiBundle(inP))
    val out = new AxiBundle(outP)
  })

  private val dropId  = outP.idBits == 0
  private val sameId  = inP.idBits == outP.idBits
  private val trackId = !sameId && !dropId && inP.idBits > 0
  private val nidBits = log2Ceil(outstanding + 1)
  private val inIdW   = math.max(inP.idBits, 1)
  private val outIdW  = math.max(outP.idBits, 1)

  private def resizeUInt(src: UInt, width: Int): UInt = {
    if (width <= 0) {
      0.U(0.W)
    } else if (src.getWidth == width) {
      src
    } else if (src.getWidth == 0) {
      0.U(width.W)
    } else if (src.getWidth > width) {
      src(width - 1, 0)
    } else {
      src.asTypeOf(UInt(width.W))
    }
  }

  private def connectAXNoId(dst: AXFlit, src: AXFlit, dstP: AxiParams, en: Bool = true.B): Unit = {
    dst.addr   := resizeUInt(src.addr, dstP.addrBits)
    dst.len    := resizeUInt(src.len, dstP.lenBits)
    // Truncating len must not drop nonzero MSBs (e.g. AXI4 len8 → AXI3 len4)
    if (src.len.getWidth > dstP.lenBits) {
      when(en) {
        if (dstP.lenBits == 0) {
          assert(src.len === 0.U, "AXI len truncated to width 0 but src.len != 0")
        } else {
          assert(
            src.len(src.len.getWidth - 1, dstP.lenBits) === 0.U,
            s"AXI len truncated: srcWidth=${src.len.getWidth} dstWidth=${dstP.lenBits}"
          )
        }
      }
    }
    dst.size   := resizeUInt(src.size, dstP.sizeBits)
    dst.burst  := resizeUInt(src.burst, dstP.burstBits)
    dst.lock   := resizeUInt(src.lock, dstP.lockBits)
    dst.cache  := resizeUInt(src.cache, dstP.cacheBits)
    dst.prot   := src.prot
    dst.qos    := resizeUInt(src.qos, dstP.qosBits)
    dst.region := resizeUInt(src.region, dstP.regionBits)
    dst.user   := resizeUInt(src.user, dstP.userBits)
  }

  private def connectAX(dst: AXFlit, src: AXFlit, dstP: AxiParams, en: Bool = true.B): Unit = {
    connectAXNoId(dst, src, dstP, en)
    dst.id := resizeUInt(src.id, dstP.idBits)
  }

  // ---- W (no ID); dataBits equal ⇒ strb width equal, forward as-is ----
  io.out.w.valid     := io.in.w.valid
  io.out.w.bits.data := io.in.w.bits.data
  io.out.w.bits.strb := io.in.w.bits.strb
  io.out.w.bits.last := resizeUInt(io.in.w.bits.last, outP.lastBits)
  io.out.w.bits.user := resizeUInt(io.in.w.bits.user, outP.userBits)
  io.in.w.ready      := io.out.w.ready

  if (sameId && !dropId) {
    // ---- same ID width: field resize only ----
    io.out.aw.valid := io.in.aw.valid
    connectAX(io.out.aw.bits, io.in.aw.bits, outP, io.in.aw.valid)
    io.in.aw.ready := io.out.aw.ready

    io.out.ar.valid := io.in.ar.valid
    connectAX(io.out.ar.bits, io.in.ar.bits, outP, io.in.ar.valid)
    io.in.ar.ready := io.out.ar.ready

    io.in.b.valid     := io.out.b.valid
    io.in.b.bits.resp := io.out.b.bits.resp
    io.in.b.bits.user := resizeUInt(io.out.b.bits.user, inP.userBits)
    io.in.b.bits.id   := io.out.b.bits.id
    io.out.b.ready    := io.in.b.ready

    io.in.r.valid     := io.out.r.valid
    io.in.r.bits.data := io.out.r.bits.data
    io.in.r.bits.resp := io.out.r.bits.resp
    io.in.r.bits.last := resizeUInt(io.out.r.bits.last, inP.lastBits)
    io.in.r.bits.user := resizeUInt(io.out.r.bits.user, inP.userBits)
    io.in.r.bits.id   := io.out.r.bits.id
    io.out.r.ready    := io.in.r.ready

  } else if (dropId) {
    // ---- no downstream ID: FIFO original IDs (in-order B/R) ----
    val awIdQ = Module(new Queue(UInt(inIdW.W), outstanding))
    val arIdQ = Module(new Queue(UInt(inIdW.W), outstanding))

    io.out.aw.valid := io.in.aw.valid && awIdQ.io.enq.ready
    connectAXNoId(io.out.aw.bits, io.in.aw.bits, outP, io.in.aw.valid)
    io.out.aw.bits.id := 0.U(0.W)
    io.in.aw.ready := io.out.aw.ready && awIdQ.io.enq.ready
    awIdQ.io.enq.valid := io.in.aw.fire
    awIdQ.io.enq.bits  := io.in.aw.bits.id

    io.out.ar.valid := io.in.ar.valid && arIdQ.io.enq.ready
    connectAXNoId(io.out.ar.bits, io.in.ar.bits, outP, io.in.ar.valid)
    io.out.ar.bits.id := 0.U(0.W)
    io.in.ar.ready := io.out.ar.ready && arIdQ.io.enq.ready
    arIdQ.io.enq.valid := io.in.ar.fire
    arIdQ.io.enq.bits  := io.in.ar.bits.id

    // B = oldest AW
    when(io.out.b.valid) {
      assert(awIdQ.io.deq.valid, "B without tracked AWID")
    }
    io.in.b.valid     := io.out.b.valid && awIdQ.io.deq.valid
    io.in.b.bits.resp := io.out.b.bits.resp
    io.in.b.bits.user := resizeUInt(io.out.b.bits.user, inP.userBits)
    io.in.b.bits.id   := awIdQ.io.deq.bits
    io.out.b.ready    := io.in.b.ready && awIdQ.io.deq.valid
    awIdQ.io.deq.ready := io.out.b.fire

    // R: queue head is current ARID until rlast dequeues it
    when(io.out.r.valid) {
      assert(arIdQ.io.deq.valid, "R without tracked ARID")
    }
    io.in.r.valid     := io.out.r.valid && arIdQ.io.deq.valid
    io.in.r.bits.data := io.out.r.bits.data
    io.in.r.bits.resp := io.out.r.bits.resp
    io.in.r.bits.last := resizeUInt(io.out.r.bits.last, inP.lastBits)
    io.in.r.bits.user := resizeUInt(io.out.r.bits.user, inP.userBits)
    io.in.r.bits.id   := arIdQ.io.deq.bits
    io.out.r.ready    := io.in.r.ready && arIdQ.io.deq.valid
    // Use downstream last (source of truth); _last is true when lastBits==0
    arIdQ.io.deq.ready := io.in.r.fire && io.out.r.bits._last

  } else if (trackId) {
    // ---- truncate / extend: scoreboard + nid ----
    class IdEntry extends Bundle {
      val valid  = Bool()
      val origId = UInt(inIdW.W)
      val outId  = UInt(outIdW.W)
      val nid    = UInt(nidBits.W)
    }

    // Write side
    val wEntries = RegInit(VecInit(Seq.fill(outstanding)(0.U.asTypeOf(new IdEntry))))
    // PickOneLow(occupied): first bit0 in occupied = first free (same as AxiReorder)
    val wFreeSel = PickOneLow(wEntries.map(_.valid))
    // Exclude entry completed this cycle (B, nid==0) so AW same-cycle sees the post-complete count.
    def wSameOutCount(outId: UInt): UInt =
      PopCount(VecInit(wEntries.map { e =>
        val live = e.valid && e.outId === outId
        val done = io.out.b.fire && e.outId === io.out.b.bits.id && e.nid === 0.U
        live && !done
      }))

    val awOutId = resizeUInt(io.in.aw.bits.id, outP.idBits)
    io.out.aw.valid := io.in.aw.valid && wFreeSel.valid
    connectAXNoId(io.out.aw.bits, io.in.aw.bits, outP, io.in.aw.valid)
    io.out.aw.bits.id := awOutId
    io.in.aw.ready := io.out.aw.ready && wFreeSel.valid

    when(io.in.aw.fire) {
      for (i <- 0 until outstanding) noPrefix {
        when(wFreeSel.bits(i)) {
          wEntries(i).valid  := true.B
          wEntries(i).origId := io.in.aw.bits.id
          wEntries(i).outId  := awOutId
          wEntries(i).nid    := wSameOutCount(awOutId)
        }
      }
    }

    val bHit   = VecInit(wEntries.map(e => e.valid && e.outId === io.out.b.bits.id && e.nid === 0.U))
    val bHitOH = bHit.asUInt
    when(io.out.b.valid) {
      assert(bHitOH.orR, "B with no matching scoreboard entry")
      assert(PopCount(bHitOH) <= 1.U, "B matched multiple nid==0 entries")
    }
    io.in.b.valid     := io.out.b.valid && bHitOH.orR
    io.in.b.bits.resp := io.out.b.bits.resp
    io.in.b.bits.user := resizeUInt(io.out.b.bits.user, inP.userBits)
    io.in.b.bits.id   := Mux1H(bHitOH, wEntries.map(_.origId))
    io.out.b.ready    := io.in.b.ready && bHitOH.orR

    when(io.out.b.fire) {
      for (i <- 0 until outstanding) noPrefix {
        when(bHit(i)) {
          wEntries(i).valid := false.B
        }.elsewhen(wEntries(i).valid && wEntries(i).outId === io.out.b.bits.id && wEntries(i).nid =/= 0.U) {
          wEntries(i).nid := wEntries(i).nid - 1.U
        }
      }
    }

    // Read side
    val rEntries = RegInit(VecInit(Seq.fill(outstanding)(0.U.asTypeOf(new IdEntry))))
    val rFreeSel = PickOneLow(rEntries.map(_.valid))
    // Exclude entry completed this cycle (rlast, nid==0) so AR same-cycle sees the post-complete count.
    def rSameOutCount(outId: UInt): UInt =
      PopCount(VecInit(rEntries.map { e =>
        val live = e.valid && e.outId === outId
        val done = io.out.r.fire && io.out.r.bits._last && e.outId === io.out.r.bits.id && e.nid === 0.U
        live && !done
      }))

    val arOutId = resizeUInt(io.in.ar.bits.id, outP.idBits)
    io.out.ar.valid := io.in.ar.valid && rFreeSel.valid
    connectAXNoId(io.out.ar.bits, io.in.ar.bits, outP, io.in.ar.valid)
    io.out.ar.bits.id := arOutId
    io.in.ar.ready := io.out.ar.ready && rFreeSel.valid

    when(io.in.ar.fire) {
      for (i <- 0 until outstanding) noPrefix {
        when(rFreeSel.bits(i)) {
          rEntries(i).valid  := true.B
          rEntries(i).origId := io.in.ar.bits.id
          rEntries(i).outId  := arOutId
          rEntries(i).nid    := rSameOutCount(arOutId)
        }
      }
    }

    // R: match rid + nid==0 each beat; free entry only on rlast (entry stays until then)
    val rHit     = VecInit(rEntries.map(e => e.valid && e.outId === io.out.r.bits.id && e.nid === 0.U))
    val rHitOH   = rHit.asUInt
    val rHitOrig = Mux1H(rHitOH, rEntries.map(_.origId))

    when(io.out.r.valid) {
      assert(rHitOH.orR, "R with no matching scoreboard entry")
      assert(PopCount(rHitOH) <= 1.U, "R matched multiple nid==0 entries")
    }
    io.in.r.valid     := io.out.r.valid && rHitOH.orR
    io.in.r.bits.data := io.out.r.bits.data
    io.in.r.bits.resp := io.out.r.bits.resp
    io.in.r.bits.last := resizeUInt(io.out.r.bits.last, inP.lastBits)
    io.in.r.bits.user := resizeUInt(io.out.r.bits.user, inP.userBits)
    io.in.r.bits.id   := rHitOrig
    io.out.r.ready    := io.in.r.ready && rHitOH.orR

    // Free on downstream rlast handshake (out.r.fire && last)
    when(io.out.r.fire && io.out.r.bits._last) {
      for (i <- 0 until outstanding) noPrefix {
        when(rHit(i)) {
          rEntries(i).valid := false.B
        }.elsewhen(rEntries(i).valid && rEntries(i).outId === io.out.r.bits.id && rEntries(i).nid =/= 0.U) {
          rEntries(i).nid := rEntries(i).nid - 1.U
        }
      }
    }

  } else {
    // inP.idBits == 0, outP.idBits > 0: invent outbound IDs as 0
    io.out.aw.valid := io.in.aw.valid
    connectAXNoId(io.out.aw.bits, io.in.aw.bits, outP, io.in.aw.valid)
    io.out.aw.bits.id := 0.U
    io.in.aw.ready := io.out.aw.ready

    io.out.ar.valid := io.in.ar.valid
    connectAXNoId(io.out.ar.bits, io.in.ar.bits, outP, io.in.ar.valid)
    io.out.ar.bits.id := 0.U
    io.in.ar.ready := io.out.ar.ready

    io.in.b.valid     := io.out.b.valid
    io.in.b.bits.resp := io.out.b.bits.resp
    io.in.b.bits.user := resizeUInt(io.out.b.bits.user, inP.userBits)
    io.in.b.bits.id   := 0.U(0.W)
    io.out.b.ready    := io.in.b.ready

    io.in.r.valid     := io.out.r.valid
    io.in.r.bits.data := io.out.r.bits.data
    io.in.r.bits.resp := io.out.r.bits.resp
    io.in.r.bits.last := resizeUInt(io.out.r.bits.last, inP.lastBits)
    io.in.r.bits.user := resizeUInt(io.out.r.bits.user, inP.userBits)
    io.in.r.bits.id   := 0.U(0.W)
    io.out.r.ready    := io.in.r.ready
  }
}
