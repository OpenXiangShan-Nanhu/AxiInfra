// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package generator

import freechips.rocketchip.util.AsyncQueueParams
import org.chipsalliance.cde.config.Config
import xs.infra.axi._

/**
  * Bridge 1 (CFG 桥) — 1 Slave × 3 Master
  *
  * From axi_bridge_config:
  *   S0: CPU m_axi_cfg  (64b data, 4b ID, 48b addr, AXI4, qos+region, io_aclk)
  *   M0: PCIe cfg       (32b data, no ID, 32b addr, AXI3-like, nonstd wstrb=32, axi_clk)
  *   M1: peri S_AXI_CFG (64b data, 4b ID, 48b addr, AXI4, qos, sys_clk)
  *   M2: PCIe pcie_s    (256b data, 11b ID, 48b addr, AXI4 mixed lock=2, axi_clk)
  *
  * Address map:
  *   M0: 0x3200_0000 + 16MB
  *   M1: 0x1000_0000 + 544MB
  *   M2: 0x6000_0000 + 128MB  and  0x40_0000_0000 + 64GB
  */
class AxiBridgeCfgConfig extends Config((site, here, up) => {
  case AxiSubsysParamsKey => AxiSubsysParams(
    // Match S0 data width so xbar is 64-bit; M0 downsizes, M2 upsizes
    internalDataBits = 64,
    slvp = Seq(
      PortParams(
        axip = AxiParams(
          addrBits   = 48,
          idBits     = 4,
          dataBits   = 64,
          lenBits    = 8,
          lockBits   = 1,
          qosBits    = 4,
          regionBits = 4
        ),
        // S0 is on the bridge main clock (io_aclk)
        async = None,
        // AxiReorder uses entry index as ID; needs log2Ceil(outstanding*2) <= idBits
        outstanding = 8,
        name  = "cpu_cfg"
      )
    ),
    mstp = Seq(
      // M0: PCIe cfg (DBI) — AXI3-like, no ID. dropId path: FIFO restores original AWID/ARID.
      PortParams(
        axip = AxiParams(
          addrBits   = 32,
          idBits     = 0,
          dataBits   = 32,
          lenBits    = 4,
          lockBits   = 2,
          qosBits    = 0,
          regionBits = 0
        ),
        async       = Some(AsyncQueueParams()),
        outstanding = 8,
        name        = "pcie_cfg",
        addr        = AddressParams(base = 0x3200_0000L, size = 0x0100_0000L)
      ),
      // M1: peri_subsys S_AXI_CFG
      PortParams(
        axip = AxiParams(
          addrBits   = 48,
          idBits     = 4,
          dataBits   = 64,
          lenBits    = 8,
          lockBits   = 1,
          qosBits    = 4,
          regionBits = 0
        ),
        async = Some(AsyncQueueParams()),
        name  = "peri_cfg",
        addr  = AddressParams(base = 0x1000_0000L, size = 0x2200_0000L)
      ),
      // M2: PCIe pcie_s — dual BAR address ranges
      PortParams(
        axip = AxiParams(
          addrBits   = 48,
          idBits     = 11,
          dataBits   = 256,
          lenBits    = 8,
          lockBits   = 2,
          qosBits    = 4,
          regionBits = 0
        ),
        async = Some(AsyncQueueParams()),
        name  = "pcie_s",
        addr  = AddressParams(
          base   = 0x6000_0000L,
          size   = 0x0800_0000L,
          extras = Seq((0x40_0000_0000L, 0x10_0000_0000L))
        )
      )
    ),
    memp = Seq()
  )
})
