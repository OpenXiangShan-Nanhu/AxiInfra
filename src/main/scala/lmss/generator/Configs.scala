package lmss.generator

import freechips.rocketchip.util.AsyncQueueParams
import lmss.axi.AxiParams
import lmss.param.{AddressParams, LmssParams, LmssParamsKey, PortParams}
import org.chipsalliance.cde.config.Config

class LmssConfig extends Config((site, here, up) => {
  case LmssParamsKey => LmssParams(
    internalDataBits = 256,
    slvp = Seq(
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_0"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_1"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_2"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_3"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_4"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_5"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_6"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "pc_7"),

      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "ec_cpu_0"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "ec_cpu_1"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "ec_cpu_2"),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), name = "ec_cpu_3"),
      PortParams(axip = AxiParams(dataBits = 512), async = Some(AsyncQueueParams()), name = "ec_tpu_0"),
      PortParams(axip = AxiParams(dataBits = 512), async = Some(AsyncQueueParams()), name = "ec_tpu_1"),
      PortParams(axip = AxiParams(dataBits = 512), async = Some(AsyncQueueParams()), name = "ec_tpu_2"),
      PortParams(axip = AxiParams(dataBits = 512), async = Some(AsyncQueueParams()), name = "ec_tpu_3"),
    ),
    mstp = Seq(
      PortParams(axip = AxiParams(dataBits = 32), async = Some(AsyncQueueParams()), name = "pmc", addr = AddressParams(0x50_8000_000L, 0x10_0000L)),
    ),
    // Memory Interleaving: 12 Bits, Base 0x8000_0000, Size: 0x40_0000_0000
    memp = Seq(
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x0000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x1000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x2000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x3000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x4000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x5000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x6000L, 0x7000L)),
      PortParams(axip = AxiParams(), async = Some(AsyncQueueParams()), addr = AddressParams(0x8000_0000L, 0x40_0000_0000L, 0x7000L, 0x7000L)),
    )
  )
})
