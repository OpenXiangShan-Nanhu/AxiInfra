package xs.infra.axi

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AxiWideToNarrowSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AxiWideToNarrow"

  private val mstParams = AxiParams(
    addrBits = 32,
    idBits = 2,
    userBits = 1,
    dataBits = 64
  )
  private val slvParams = mstParams.copy(dataBits = 32)
  private val id10MstParams = mstParams.copy(idBits = 10)
  private val id10SlvParams = id10MstParams.copy(dataBits = 32)

  private case class AwStim(
    addr: BigInt,
    id: BigInt,
    len: Int = 0,
    size: Int = 2,
    burst: Int = 1,
    cache: Int = 0
  )
  private case class WStim(
    data: BigInt,
    strb: BigInt = 0xff,
    last: Boolean = true
  )
  private case class BStim(id: BigInt, resp: Int = 0)
  private case class CycleStim(
    mstAw: Option[AwStim] = None,
    mstW: Option[WStim] = None,
    mstBReady: Boolean = true,
    slvAwReady: Boolean = true,
    slvWReady: Boolean = true,
    slvB: Option[BStim] = None
  )

  private def aw(addr: BigInt, id: BigInt, len: Int = 0, size: Int = 2): Option[AwStim] =
    Some(AwStim(addr = addr, id = id, len = len, size = size))

  private def w(data: BigInt, strb: BigInt = 0xff, last: Boolean = true): Option[WStim] =
    Some(WStim(data = data, strb = strb, last = last))

  private def b(id: BigInt, resp: Int = 0): Option[BStim] =
    Some(BStim(id = id, resp = resp))

  private def idleInputs(dut: AxiWideToNarrow): Unit = {
    dut.io.mst.aw.valid.poke(false.B)
    dut.io.mst.aw.bits.id.poke(0.U)
    dut.io.mst.aw.bits.addr.poke(0.U)
    dut.io.mst.aw.bits.len.poke(0.U)
    dut.io.mst.aw.bits.size.poke(3.U)
    dut.io.mst.aw.bits.burst.poke(1.U)
    dut.io.mst.aw.bits.lock.poke(0.U)
    dut.io.mst.aw.bits.cache.poke(0.U)
    dut.io.mst.aw.bits.prot.poke(0.U)
    dut.io.mst.aw.bits.qos.poke(0.U)
    dut.io.mst.aw.bits.region.poke(0.U)
    dut.io.mst.aw.bits.user.poke(0.U)

    dut.io.mst.ar.valid.poke(false.B)
    dut.io.mst.ar.bits.id.poke(0.U)
    dut.io.mst.ar.bits.addr.poke(0.U)
    dut.io.mst.ar.bits.len.poke(0.U)
    dut.io.mst.ar.bits.size.poke(3.U)
    dut.io.mst.ar.bits.burst.poke(1.U)
    dut.io.mst.ar.bits.lock.poke(0.U)
    dut.io.mst.ar.bits.cache.poke(0.U)
    dut.io.mst.ar.bits.prot.poke(0.U)
    dut.io.mst.ar.bits.qos.poke(0.U)
    dut.io.mst.ar.bits.region.poke(0.U)
    dut.io.mst.ar.bits.user.poke(0.U)

    dut.io.mst.w.valid.poke(false.B)
    dut.io.mst.w.bits.data.poke(0.U)
    dut.io.mst.w.bits.strb.poke(0.U)
    dut.io.mst.w.bits.last.poke(0.U)
    dut.io.mst.w.bits.user.poke(0.U)
    dut.io.mst.b.ready.poke(false.B)
    dut.io.mst.r.ready.poke(false.B)

    dut.io.slv.aw.ready.poke(false.B)
    dut.io.slv.ar.ready.poke(false.B)
    dut.io.slv.w.ready.poke(false.B)
    dut.io.slv.b.valid.poke(false.B)
    dut.io.slv.b.bits.id.poke(0.U)
    dut.io.slv.b.bits.resp.poke(0.U)
    dut.io.slv.b.bits.user.poke(0.U)
    dut.io.slv.r.valid.poke(false.B)
    dut.io.slv.r.bits.id.poke(0.U)
    dut.io.slv.r.bits.data.poke(0.U)
    dut.io.slv.r.bits.resp.poke(0.U)
    dut.io.slv.r.bits.last.poke(0.U)
    dut.io.slv.r.bits.user.poke(0.U)
  }

  private def driveMstAw(dut: AxiWideToNarrow, aw: Option[AwStim]): Unit = {
    dut.io.mst.aw.valid.poke(aw.nonEmpty.B)
    aw.foreach { a =>
      dut.io.mst.aw.bits.id.poke(a.id.U)
      dut.io.mst.aw.bits.addr.poke(a.addr.U)
      dut.io.mst.aw.bits.len.poke(a.len.U)
      dut.io.mst.aw.bits.size.poke(a.size.U)
      dut.io.mst.aw.bits.burst.poke(a.burst.U)
      dut.io.mst.aw.bits.cache.poke(a.cache.U)
    }
  }

  private def driveMstW(dut: AxiWideToNarrow, w: Option[WStim]): Unit = {
    dut.io.mst.w.valid.poke(w.nonEmpty.B)
    w.foreach { beat =>
      dut.io.mst.w.bits.data.poke(beat.data.U)
      dut.io.mst.w.bits.strb.poke(beat.strb.U)
      dut.io.mst.w.bits.last.poke(beat.last.B)
      dut.io.mst.w.bits.user.poke(0.U)
    }
  }

  private def driveSlvB(dut: AxiWideToNarrow, b: Option[BStim]): Unit = {
    dut.io.slv.b.valid.poke(b.nonEmpty.B)
    b.foreach { resp =>
      dut.io.slv.b.bits.id.poke(resp.id.U)
      dut.io.slv.b.bits.resp.poke(resp.resp.U)
      dut.io.slv.b.bits.user.poke(0.U)
    }
  }

  private def observeSlvAw(dut: AxiWideToNarrow): Option[(BigInt, BigInt)] = {
    val fire = dut.io.slv.aw.valid.peek().litToBoolean &&
      dut.io.slv.aw.ready.peek().litToBoolean
    Option.when(fire) {
      dut.io.slv.aw.bits.addr.peek().litValue -> dut.io.slv.aw.bits.id.peek().litValue
    }
  }

  private def driveCycle(dut: AxiWideToNarrow, stim: CycleStim): Option[(BigInt, BigInt)] = {
    driveMstAw(dut, stim.mstAw)
    driveMstW(dut, stim.mstW)
    driveSlvB(dut, stim.slvB)
    dut.io.mst.b.ready.poke(stim.mstBReady.B)
    dut.io.slv.aw.ready.poke(stim.slvAwReady.B)
    dut.io.slv.w.ready.poke(stim.slvWReady.B)

    val observed = observeSlvAw(dut)
    dut.clock.step()
    observed
  }

  private def clearWriteStimulus(dut: AxiWideToNarrow): Unit = {
    driveMstAw(dut, None)
    driveMstW(dut, None)
    driveSlvB(dut, None)
    dut.io.mst.b.ready.poke(true.B)
    dut.io.slv.aw.ready.poke(true.B)
    dut.io.slv.w.ready.poke(true.B)
  }

  private def runStimulus(
    dut: AxiWideToNarrow,
    stim: Seq[CycleStim],
    drainCycles: Int = 16
  ): Seq[(BigInt, BigInt)] = {
    val seen = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    stim.foreach { s =>
      seen ++= driveCycle(dut, s)
    }
    clearWriteStimulus(dut)
    for (_ <- 0 until drainCycles) {
      seen ++= observeSlvAw(dut)
      dut.clock.step()
    }
    seen.toSeq
  }

  private def waitForSlvAwValid(
      dut: AxiWideToNarrow,
      cycles: Int = 16
  ): Unit = {
    var left = cycles
    while (left > 0 && !dut.io.slv.aw.valid.peek().litToBoolean) {
      dut.clock.step()
      left -= 1
    }
    dut.io.slv.aw.valid.expect(true.B)
  }

  private def expectSlvWIdle(
      dut: AxiWideToNarrow,
      cycles: Int,
      clue: String
  ): Unit = {
    for (_ <- 0 until cycles) {
      withClue(clue) {
        dut.io.slv.w.valid.expect(false.B)
      }
      dut.clock.step()
    }
  }

  it should "hold back the next burst W beats until its AW is accepted so wlast stays aligned" in {
    simulate(new AxiWideToNarrow(mstParams, slvParams, buffer = 2)) { dut =>
      idleInputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.slv.aw.ready.poke(true.B)
      dut.io.slv.w.ready.poke(false.B)
      dut.io.mst.b.ready.poke(true.B)

      dut.io.mst.aw.valid.poke(true.B)
      dut.io.mst.aw.bits.id.poke(1.U)
      dut.io.mst.aw.bits.addr.poke(0x40.U)
      dut.io.mst.aw.bits.len.poke(0.U)
      dut.io.mst.aw.bits.size.poke(3.U)
      dut.io.mst.aw.bits.burst.poke(1.U)
      dut.io.mst.aw.ready.expect(true.B)
      dut.clock.step()
      dut.io.mst.aw.valid.poke(false.B)

      waitForSlvAwValid(dut)
      dut.io.slv.aw.valid.expect(true.B)
      dut.clock.step()

      dut.io.mst.w.valid.poke(true.B)
      dut.io.mst.w.bits.data.poke("h8877665544332211".U)
      dut.io.mst.w.bits.strb.poke("hff".U)
      dut.io.mst.w.bits.last.poke(true.B)
      dut.io.mst.w.bits.user.poke(0.U)
      dut.io.mst.w.ready.expect(true.B)
      dut.clock.step()
      dut.io.mst.w.valid.poke(false.B)

      // Keep the second AW pending while its W beats fill the downstream W queue.
      dut.io.slv.aw.ready.poke(false.B)

      dut.io.mst.aw.valid.poke(true.B)
      dut.io.mst.aw.bits.id.poke(2.U)
      dut.io.mst.aw.bits.addr.poke(0x80.U)
      dut.io.mst.aw.bits.len.poke(0.U)
      dut.io.mst.aw.bits.size.poke(3.U)
      dut.io.mst.aw.bits.burst.poke(1.U)
      dut.io.mst.aw.ready.expect(true.B)
      dut.clock.step()
      dut.io.mst.aw.valid.poke(false.B)

      dut.io.mst.w.valid.poke(true.B)
      dut.io.mst.w.bits.data.poke("h00ffeeddccbbaa99".U)
      dut.io.mst.w.bits.strb.poke("hff".U)
      dut.io.mst.w.bits.last.poke(true.B)
      dut.io.mst.w.bits.user.poke(0.U)
      dut.io.mst.w.ready.expect(true.B)
      dut.clock.step()
      dut.io.mst.w.valid.poke(false.B)

      // The W queue is now full (buffer = 2, seg = 2 => capacity = 4 narrow beats).
      dut.io.slv.w.ready.poke(true.B)

      dut.io.slv.w.valid.expect(true.B)
      dut.io.slv.w.bits.data.expect("h44332211".U)
      dut.io.slv.w.bits.last.expect(false.B)
      dut.clock.step()

      dut.io.slv.w.valid.expect(true.B)
      dut.io.slv.w.bits.data.expect("h88776655".U)
      dut.io.slv.w.bits.last.expect(true.B)
      dut.clock.step()

      // The next AW is still blocked here, so none of its W beats may leak out.
      dut.io.slv.aw.valid.expect(true.B)
      dut.io.slv.aw.bits.id.expect(2.U)
      expectSlvWIdle(
        dut,
        cycles = 3,
        clue = "the second burst must not issue W beats before its AW handshake, otherwise wCounter advances early"
      )

      dut.io.slv.aw.ready.poke(true.B)
      dut.io.slv.aw.valid.expect(true.B)
      dut.clock.step()

      dut.io.slv.w.valid.expect(true.B)
      dut.io.slv.w.bits.data.expect("hccbbaa99".U)
      dut.io.slv.w.bits.last.expect(false.B)
      dut.clock.step()

      dut.io.slv.w.valid.expect(true.B)
      dut.io.slv.w.bits.data.expect("h00ffeedd".U)
      dut.io.slv.w.bits.last.expect(true.B)
      dut.clock.step()

      dut.io.slv.w.valid.expect(false.B)
      dut.io.slv.aw.valid.expect(false.B)
    }
  }
}
