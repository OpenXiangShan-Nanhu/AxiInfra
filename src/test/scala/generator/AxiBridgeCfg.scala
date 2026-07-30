// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package generator

import org.chipsalliance.cde.config.Parameters

/**
  * Bridge 1 top-level: AXI CFG bridge (1 slave × 3 masters).
  *
  * Ports (see AxiBridgeCfgConfig / axi_bridge_config):
  *   clock/reset          — bridge main domain (io_aclk / io_aresetn-async)
  *   s_axi_cpu_cfg_*      — CPU m_axi_cfg slave port (64b / 4b ID / 48b addr)
  *   m_axi_pcie_cfg_*     — PCIe DBI master port (32b / no ID / 32b addr, wstrb=32)
  *   m_aclk_pcie_cfg, m_arst_pcie_cfg
  *   m_axi_peri_cfg_*     — peri S_AXI_CFG master port (64b / 4b ID / 48b addr)
  *   m_aclk_peri_cfg, m_arst_peri_cfg
  *   m_axi_pcie_s_*       — PCIe BAR slave master port (256b / 11b ID / 48b addr)
  *   m_aclk_pcie_s, m_arst_pcie_s
  */
class AxiBridgeCfg(implicit p: Parameters) extends AxiSubsysTop {
  override val desiredName = "AxiBridgeCfg"
}
