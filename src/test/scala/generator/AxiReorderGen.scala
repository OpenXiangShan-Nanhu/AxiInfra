package generator

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage
import xs.infra.axi._
// 如果 MyModule 不在 generator 包下，需要在这里 import 它的真实包名。
// 例如：import myproject.module.MyModule

object AxiReorderGen extends App {
private val firrtlOpts =
    if (args.nonEmpty) args
    else Array("--target", "systemverilog", "--split-verilog", "-td", "build/rtl")

  (new ChiselStage).execute(
    firrtlOpts,
    Seq(
      ChiselGeneratorAnnotation(() => new AxiReorder(AxiParams(), buffer = 64))
    )
  )
}