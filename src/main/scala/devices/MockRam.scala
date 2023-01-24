package devices

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import core_._


class MockRam(ramDataFile: String = null, printLog: Boolean = true) extends Module {
  val io = IO(Flipped(new RAMOp))

  val mem = Mem(0x800000, UInt(8.W))
  if(ramDataFile != null) {
    loadMemoryFromFile(mem, ramDataFile)
  }

  val mode_reg = RegNext(io.mode, init=0.U)
  val addr_reg = RegNext(io.addr, init=0.U)

  io.ok := mode_reg =/= RAMMode.NOP
  val data = Cat((0 until 4).reverse.map(i => mem(addr_reg + i.U)))

  // Mem write happens at rising edge.
  // So must not use Reg.
  switch(io.mode) {
    is(RAMMode.SW) {
      for (i <- 0 until 4)
        mem(io.addr + i.U) := io.wdata(i * 8 + 7, i * 8)
      if (printLog)
        printf("[RAM] SW: [%x]=%x\n", addr_reg, io.wdata)
    }
    is(RAMMode.SH) {
      mem(io.addr + 1.U) := io.wdata(15, 8)
      mem(io.addr) := io.wdata(7, 0)
      if (printLog)
        printf("[RAM] SH: [%x]=%x\n", addr_reg, io.wdata)
    }
    is(RAMMode.SB) {
      mem(io.addr) := io.wdata(7, 0)
      if (printLog)
        printf("[RAM] SB: [%x]=%x\n", addr_reg, io.wdata)
    }
  }

  // To read at rising edge, have to use Reg.
  io.rdata := 0.U
  switch(mode_reg) {
    is(RAMMode.LW) {
      io.rdata := data
      if (printLog)
        printf("[RAM] LW: [%x]->%x\n", addr_reg, io.rdata)
    }
    is(RAMMode.LH) {
      io.rdata := Cat(Mux(data(15), 0xffff.U, 0.U), data(15, 0))
      if (printLog)
        printf("[RAM] LH: [%x]->%x\n", addr_reg, io.rdata)
    }
    is(RAMMode.LB) {
      io.rdata := Cat(Mux(data(7), 0xffffff.U, 0.U), data(7, 0))
      if (printLog)
        printf("[RAM] LB: [%x]->%x\n", addr_reg, io.rdata)
    }
    is(RAMMode.LHU) {
      io.rdata := data(15, 0).zext.asUInt
      if (printLog)
        printf("[RAM] LHU: [%x]->%x\n", addr_reg, io.rdata)
    }
    is(RAMMode.LBU) {
      io.rdata := data(7, 0).zext.asUInt
      if (printLog)
        printf("[RAM] LBU: [%x]->%x\n", addr_reg, io.rdata)
    }
  }
}
