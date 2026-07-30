package xs.infra.axi

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class AxiReorderHarness(params: AxiParams, buffer: Int) extends Module {
  val io = IO(new Bundle {
    val mst = Flipped(new AxiBundle(params))
    val slv = new AxiBundle(params)
  })

  private val reorder = withReset(reset.asAsyncReset) { Module(new AxiReorder(params, buffer)) }
  reorder.io.mst <> io.mst
  io.slv <> reorder.io.slv
}

class AxiReorderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AxiReorder"

  private val params = AxiParams(
    addrBits = 32,
    idBits = 3,
    userBits = 1,
    dataBits = 32
  )

  private def idleInputs(dut: AxiReorderHarness): Unit = {
    dut.io.mst.aw.valid.poke(false.B)
    dut.io.mst.aw.bits.id.poke(0.U)
    dut.io.mst.aw.bits.addr.poke(0.U)
    dut.io.mst.aw.bits.len.poke(0.U)
    dut.io.mst.aw.bits.size.poke(2.U)
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
    dut.io.mst.ar.bits.size.poke(2.U)
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
    dut.io.mst.b.ready.poke(true.B)
    dut.io.mst.r.ready.poke(true.B)

    dut.io.slv.aw.ready.poke(true.B)
    dut.io.slv.ar.ready.poke(true.B)
    dut.io.slv.w.ready.poke(true.B)
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

  private def driveAr(dut: AxiReorderHarness, valid: Boolean, id: Int): Unit = {
    dut.io.mst.ar.valid.poke(valid.B)
    dut.io.mst.ar.bits.id.poke(id.U)
    dut.io.mst.ar.bits.addr.poke(0x100.U)
  }

  private def driveAw(dut: AxiReorderHarness, valid: Boolean, id: Int): Unit = {
    dut.io.mst.aw.valid.poke(valid.B)
    dut.io.mst.aw.bits.id.poke(id.U)
    dut.io.mst.aw.bits.addr.poke(0x200.U)
  }

  private def reset(dut: AxiReorderHarness): Unit = {
    idleInputs(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  it should "make a same-ID AR immediately eligible when its predecessor completes concurrently" in {
    simulate(new AxiReorderHarness(params, buffer = 4)) { dut =>
      reset(dut)

      driveAr(dut, valid = true, id = 3)
      dut.io.mst.ar.ready.expect(true.B)
      dut.clock.step()

      driveAr(dut, valid = false, id = 0)
      dut.io.slv.ar.valid.expect(true.B)
      dut.io.slv.ar.bits.id.expect(0.U)
      dut.clock.step()

      driveAr(dut, valid = true, id = 3)
      dut.io.slv.r.valid.poke(true.B)
      dut.io.slv.r.bits.id.poke(0.U)
      dut.io.slv.r.bits.last.poke(1.U)
      dut.io.mst.ar.ready.expect(true.B)
      dut.io.mst.r.bits.id.expect(3.U)
      dut.clock.step()

      driveAr(dut, valid = false, id = 0)
      dut.io.slv.r.valid.poke(false.B)
      dut.io.slv.ar.valid.expect(true.B)
      dut.io.slv.ar.bits.id.expect(1.U)
    }
  }

  it should "preserve the remaining predecessor count for a concurrent same-ID AR" in {
    simulate(new AxiReorderHarness(params, buffer = 4)) { dut =>
      reset(dut)

      driveAr(dut, valid = true, id = 3)
      dut.clock.step()
      driveAr(dut, valid = false, id = 0)
      dut.io.slv.ar.bits.id.expect(0.U)
      dut.clock.step()

      // B is accepted behind A and must remain blocked with nid=1.
      driveAr(dut, valid = true, id = 3)
      dut.io.slv.ar.valid.expect(false.B)
      dut.clock.step()

      // Complete A while accepting C. B becomes ready; C must still wait for B.
      driveAr(dut, valid = true, id = 3)
      dut.io.slv.r.valid.poke(true.B)
      dut.io.slv.r.bits.id.poke(0.U)
      dut.io.slv.r.bits.last.poke(1.U)
      dut.clock.step()

      driveAr(dut, valid = false, id = 0)
      dut.io.slv.r.valid.poke(false.B)
      dut.io.slv.ar.valid.expect(true.B)
      dut.io.slv.ar.bits.id.expect(1.U)
      dut.clock.step()

      // C must not issue before B completes.
      dut.io.slv.ar.valid.expect(false.B)
      dut.io.slv.r.valid.poke(true.B)
      dut.io.slv.r.bits.id.poke(1.U)
      dut.io.slv.r.bits.last.poke(1.U)
      dut.clock.step()

      dut.io.slv.r.valid.poke(false.B)
      dut.io.slv.ar.valid.expect(true.B)
      dut.io.slv.ar.bits.id.expect(2.U)
    }
  }

  it should "make a same-ID AW immediately eligible when its predecessor completes concurrently" in {
    simulate(new AxiReorderHarness(params, buffer = 4)) { dut =>
      reset(dut)

      driveAw(dut, valid = true, id = 5)
      dut.io.mst.aw.ready.expect(true.B)
      dut.clock.step()

      driveAw(dut, valid = false, id = 0)
      dut.io.slv.aw.valid.expect(true.B)
      dut.io.slv.aw.bits.id.expect(0.U)
      dut.clock.step()

      dut.io.mst.w.valid.poke(true.B)
      dut.io.mst.w.bits.data.poke(0x12345678.U)
      dut.io.mst.w.bits.strb.poke(0xf.U)
      dut.io.mst.w.bits.last.poke(1.U)
      dut.io.mst.w.ready.expect(true.B)
      dut.clock.step()
      dut.io.mst.w.valid.poke(false.B)
      dut.clock.step()

      driveAw(dut, valid = true, id = 5)
      dut.io.slv.b.valid.poke(true.B)
      dut.io.slv.b.bits.id.poke(0.U)
      dut.io.mst.aw.ready.expect(true.B)
      dut.io.mst.b.bits.id.expect(5.U)
      dut.clock.step()

      driveAw(dut, valid = false, id = 0)
      dut.io.slv.b.valid.poke(false.B)
      dut.io.slv.aw.valid.expect(true.B)
      dut.io.slv.aw.bits.id.expect(1.U)
    }
  }
}
