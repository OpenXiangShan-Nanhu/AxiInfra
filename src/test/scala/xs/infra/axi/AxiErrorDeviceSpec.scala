package xs.infra.axi

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AxiErrorDeviceSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AxiErrorDevice"

  private val axiParams = AxiParams(
    addrBits = 32,
    idBits = 4,
    userBits = 1,
    dataBits = 64
  )

  it should "return B only after WLAST and keep DECERR" in {
    simulate(new AxiErrorDevice(axiParams)) { dut =>
      dut.io.axi.aw.valid.poke(false.B)
      dut.io.axi.w.valid.poke(false.B)
      dut.io.axi.ar.valid.poke(false.B)
      dut.io.axi.b.ready.poke(true.B)
      dut.io.axi.r.ready.poke(true.B)
      dut.io.axi.aw.bits.id.poke(0.U)
      dut.io.axi.aw.bits.user.poke(0.U)
      dut.io.axi.w.bits.last.poke(false.B)
      dut.io.axi.w.bits.data.poke(0.U)
      dut.io.axi.w.bits.strb.poke(0.U)
      dut.io.axi.w.bits.user.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.axi.b.valid.expect(false.B)
      dut.io.axi.w.ready.expect(false.B)

      dut.io.axi.aw.valid.poke(true.B)
      dut.io.axi.aw.bits.id.poke(3.U)
      dut.io.axi.aw.bits.user.poke(1.U)
      dut.io.axi.aw.ready.expect(true.B)
      dut.clock.step()
      dut.io.axi.aw.valid.poke(false.B)

      dut.io.axi.b.valid.expect(false.B)
      dut.io.axi.w.ready.expect(true.B)

      dut.io.axi.w.valid.poke(true.B)
      dut.io.axi.w.bits.last.poke(false.B)
      dut.clock.step()
      dut.io.axi.b.valid.expect(false.B)

      dut.io.axi.w.bits.last.poke(true.B)
      dut.clock.step()
      dut.io.axi.w.valid.poke(false.B)

      dut.io.axi.b.valid.expect(true.B)
      dut.io.axi.b.bits.id.expect(3.U)
      dut.io.axi.b.bits.resp.expect("b11".U)
      dut.clock.step()
      dut.io.axi.b.valid.expect(false.B)
    }
  }
}
