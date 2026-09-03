// SPDX-License-Identifier: MulanPSL-2.0
// Copyright (c) 2025-2026 RedRISC Technology Co. Ltd.

package xs.infra.axi

import chisel3._
import xs.utils.arb.{BaseArbiter, SelNto1, XsRRArbiter}

class OneStageConditionArbiter[T <: Data](gen: T, size: Int, selfCmpOtherFunc: (T, T) => Bool) extends BaseArbiter(gen, size) {
  require(size > 0)

  if (size == 1) {
    io.in(0).ready := io.out.ready
    io.out.valid   := io.in(0).valid
    io.out.bits    := io.in(0).bits
    io.chosen      := 0.U
  } else {
    val selector = Module(new SelNto1(gen, size, selfCmpOtherFunc))
    val selArb   = Module(new XsRRArbiter(gen, size))

    for (i <- io.in.indices) {
      selector.io.in(i).valid := io.in(i).valid
      selector.io.in(i).bits  := io.in(i).bits
      io.in(i).ready          := selArb.io.in(i).fire

      selArb.io.in(i).valid := selector.io.out(i)
      selArb.io.in(i).bits  := io.in(i).bits

      when(selector.io.out(i)) {
        assert(io.in(i).valid)
      }
    }
    io.chosen := selArb.io.chosen
    io.out    <> selArb.io.out
  }
}
