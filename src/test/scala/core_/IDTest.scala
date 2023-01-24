package core_

import chisel3._
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}


class IDTest(t: ID) extends PeekPokeTester(t) {
  reset(10)
  poke(t.io.iff.pc, "h_8000_0000".U(32.W))
  poke(t.io.iff.excep.valid, false.B)
  poke(t.io.flush, false.B)
  poke(t.io.ex.ready, true)
  poke(t.io.exWrRegOp.addr, 0) // no forwarding
  poke(t.io.memWrRegOp.addr, 0) // no forwarding
  poke(t.io.iff.inst, "b111111111110_00001_010_00010_0010011".U)
  poke(t.io.reg.read1.data,7)
  poke(t.io.reg.read2.data,8)
  step(1)
  expect(t.io.iff.branch.valid, false.B)
  expect(t.io.ex.aluOp.rd1, 7)
  expect(t.io.ex.aluOp.rd2, "h_ffff_fffe".U)
  expect(t.io.ex.aluOp.opt, OptCode.SLT)

  poke(t.io.iff.pc, "h_8000_0010".U(32.W))
  poke(t.io.iff.inst, "h_fea0_9ce3".U) // bne x1 x10 -8
  // 1111_1110_1010_0000_1001_1100_1110_0011
  poke(t.io.reg.read1.data, 1)
  poke(t.io.reg.read2.data, 10)
  step(1)
  expect(t.d.type_, InstType.B)
  expect(t.d.opt, BType.BNE)
  expect(t.d.bt, "b0_1010".U)
  expect(t.d.l, true.B)
  expect(t.io.iff.branch.valid, true.B)
  expect(t.io.iff.branch.bits, "h_8000_0008".U(32.W))
}

class IDTester extends ChiselFlatSpec {
    val args = Array[String]()
    "new ID module" should "pass test" in {
      iotesters.Driver.execute(args, () => new ID()) {
        c => new IDTest(c)
      } should be (true)
    }
}
