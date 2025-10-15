package lmss.param

import chisel3._
import freechips.rocketchip.util.AsyncQueueParams
import lmss.axi.AxiParams
import org.chipsalliance.cde.config.Field

case object LmssParamsKey extends Field[LmssParams]

case class AddressParams(
  base: Long = 0x0L,
  size: Long = 0x0L,
  intv: Long = 0x0L,
  mask: Long = 0x0L
) {
  def test(addr:UInt):Bool = {
    val min = base.U
    val max = (base + size).U
    min <= addr && addr < max && (addr & mask.U) === intv.U
  }
}

case class PortParams(
  axip: AxiParams = AxiParams(),
  async: Option[AsyncQueueParams] = None,
  addr: AddressParams = AddressParams(),
  pipe: Int = 2,
  outstanding: Int = 32,
  name: String = ""
) 

case class LmssParams(
  slvp:Seq[PortParams] = Seq(),
  mstp:Seq[PortParams] = Seq(),
  memp:Seq[PortParams] = Seq(),
  internalDataBits:Int = 256
)