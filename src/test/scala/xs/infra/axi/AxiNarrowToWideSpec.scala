package xs.infra.axi

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AxiNarrowToWideSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AxiNarrowToWide"

  private val mstParams = AxiParams(
    addrBits = 32,
    idBits = 2,
    userBits = 1,
    dataBits = 32
  )
  private val slvParams = mstParams.copy(dataBits = 64)

  private def wide(upper: BigInt, lower: BigInt): BigInt =
    (upper << 32) | lower

  it should "select R data using the queued beat when a new slave beat arrives concurrently" in {
    simulate(new AxiNarrowToWide(mstParams, slvParams, buffer = 4)) { dut =>
      def idleInputs(): Unit = {
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

      def sendAr(id: Int, addr: BigInt): Unit = {
        dut.io.mst.ar.valid.poke(true.B)
        dut.io.mst.ar.bits.id.poke(id.U)
        dut.io.mst.ar.bits.addr.poke(addr.U)
        dut.io.mst.ar.bits.len.poke(0.U)
        dut.io.mst.ar.bits.size.poke(2.U)
        dut.io.mst.ar.bits.burst.poke(1.U)
        dut.io.mst.ar.bits.lock.poke(0.U)
        dut.io.mst.ar.bits.cache.poke(0.U)
        dut.io.mst.ar.bits.prot.poke(0.U)
        dut.io.mst.ar.bits.qos.poke(0.U)
        dut.io.mst.ar.bits.region.poke(0.U)
        dut.io.mst.ar.bits.user.poke(0.U)
        dut.io.mst.ar.ready.expect(true.B)
        dut.clock.step()
      }

      def pokeSlvR(valid: Boolean, id: Int, data: BigInt): Unit = {
        dut.io.slv.r.valid.poke(valid.B)
        dut.io.slv.r.bits.id.poke(id.U)
        dut.io.slv.r.bits.data.poke(data.U)
        dut.io.slv.r.bits.resp.poke(0.U)
        dut.io.slv.r.bits.last.poke(1.U)
        dut.io.slv.r.bits.user.poke(0.U)
      }

      idleInputs()
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.slv.ar.ready.poke(true.B)
      sendAr(id = 0, addr = 0x0)
      sendAr(id = 1, addr = 0x4)
      dut.io.mst.ar.valid.poke(false.B)

      val firstLower = BigInt("01020304", 16)
      val firstUpper = BigInt("aabbccdd", 16)
      val secondLower = BigInt("11223344", 16)
      val secondUpper = BigInt("55667788", 16)

      dut.io.mst.r.ready.poke(false.B)
      pokeSlvR(valid = true, id = 0, data = wide(firstUpper, firstLower))
      dut.io.slv.r.ready.expect(true.B)
      dut.clock.step()

      // The first response is queued.  While it is dequeued, a second response
      // with a different ID and different lane arrives on the slave side.
      pokeSlvR(valid = true, id = 1, data = wide(secondUpper, secondLower))
      dut.io.mst.r.ready.poke(true.B)
      dut.io.mst.r.valid.expect(true.B)
      dut.io.mst.r.bits.id.expect(0.U)
      dut.io.mst.r.bits.data.expect(firstLower.U(32.W))
      dut.clock.step()

      pokeSlvR(valid = false, id = 1, data = wide(secondUpper, secondLower))
      dut.io.mst.r.valid.expect(true.B)
      dut.io.mst.r.bits.id.expect(1.U)
      dut.io.mst.r.bits.data.expect(secondUpper.U(32.W))
    }
  }

  it should "select R data from the oldest outstanding read with the same ID" in {
    simulate(new AxiNarrowToWide(mstParams, slvParams, buffer = 4)) { dut =>
      def idleInputs(): Unit = {
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

      def sendAr(id: Int, addr: BigInt): Unit = {
        dut.io.mst.ar.valid.poke(true.B)
        dut.io.mst.ar.bits.id.poke(id.U)
        dut.io.mst.ar.bits.addr.poke(addr.U)
        dut.io.mst.ar.bits.len.poke(0.U)
        dut.io.mst.ar.bits.size.poke(2.U)
        dut.io.mst.ar.bits.burst.poke(1.U)
        dut.io.mst.ar.bits.lock.poke(0.U)
        dut.io.mst.ar.bits.cache.poke(0.U)
        dut.io.mst.ar.bits.prot.poke(0.U)
        dut.io.mst.ar.bits.qos.poke(0.U)
        dut.io.mst.ar.bits.region.poke(0.U)
        dut.io.mst.ar.bits.user.poke(0.U)
        dut.io.mst.ar.ready.expect(true.B)
        dut.clock.step()
      }

      def pokeSlvR(valid: Boolean, id: Int, data: BigInt): Unit = {
        dut.io.slv.r.valid.poke(valid.B)
        dut.io.slv.r.bits.id.poke(id.U)
        dut.io.slv.r.bits.data.poke(data.U)
        dut.io.slv.r.bits.resp.poke(0.U)
        dut.io.slv.r.bits.last.poke(1.U)
        dut.io.slv.r.bits.user.poke(0.U)
      }

      idleInputs()
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.slv.ar.ready.poke(true.B)
      sendAr(id = 0, addr = 0x0)
      sendAr(id = 0, addr = 0x0)
      dut.io.mst.ar.valid.poke(false.B)

      val firstLower = BigInt("01020304", 16)
      val firstUpper = BigInt("aabbccdd", 16)
      val secondLower = BigInt("11223344", 16)
      val secondUpper = BigInt("55667788", 16)

      dut.io.mst.r.ready.poke(false.B)
      pokeSlvR(valid = true, id = 0, data = wide(firstUpper, firstLower))
      dut.io.slv.r.ready.expect(true.B)
      dut.clock.step()

      pokeSlvR(valid = false, id = 0, data = wide(firstUpper, firstLower))
      dut.io.mst.r.ready.poke(true.B)
      dut.io.mst.r.valid.expect(true.B)
      dut.io.mst.r.bits.data.expect(firstLower.U(32.W))
      dut.clock.step()

      dut.io.mst.r.ready.poke(false.B)
      pokeSlvR(valid = true, id = 0, data = wide(secondUpper, secondLower))
      dut.io.slv.r.ready.expect(true.B)
      dut.clock.step()

      pokeSlvR(valid = false, id = 0, data = wide(secondUpper, secondLower))
      dut.io.mst.r.ready.poke(true.B)
      dut.io.mst.r.valid.expect(true.B)
      dut.io.mst.r.bits.data.expect(secondLower.U(32.W))
      dut.clock.step()
    }
  }
}
