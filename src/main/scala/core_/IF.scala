package core_

import chisel3._
import chisel3.util._


class IF extends Module {
  val io = IO(new Bundle {
    val mmu = new MMUOp

    val id = new IF_ID
//    class IF_ID extends Bundle {
//      def pc     = excep.pc
//      val inst   = Output(UInt(32.W))
//      val excep  = Output(new Exception)
//
//      val branch = Input(Valid(UInt(32.W)))
//      val ready  = Input(Bool())
//    }
  })

  val pc_reg         = RegInit(Const.PC_INIT)
  val log_branch_reg = RegInit(0.U.asTypeOf(Valid(UInt(32.W))))
  val log_inst_reg   = RegInit(0.U.asTypeOf(Valid(UInt(32.W))))

  val mmu_inst = Wire(Valid(UInt(32.W)))
  mmu_inst.valid := io.mmu.ok
  mmu_inst.bits := io.mmu.rdata

  val branch = Mux(io.id.branch.valid, io.id.branch, log_branch_reg)
  val inst   = Mux(log_inst_reg.valid, log_inst_reg, mmu_inst)

  // Log branch & inst
  when(io.id.branch.valid) {
    log_branch_reg := io.id.branch
  }
  when(!log_inst_reg.valid && io.mmu.ok) {
    log_inst_reg.valid := true.B
    log_inst_reg.bits := io.mmu.rdata
  }

  val stall = !inst.valid || !io.id.ready

  // Change status only when mmu.ok
  when(!stall) {
    pc_reg := PriorityMux(Seq(
      (branch.valid, branch.bits),
      (true.B,       pc_reg + 4.U)))
    log_branch_reg := 0.U.asTypeOf(Valid(UInt(32.W))) // Clear branch log
    log_inst_reg   := 0.U.asTypeOf(Valid(UInt(32.W))) // Clear inst log
  }

  // instruction fetch
  io.mmu.addr  := pc_reg; // fetch current instruction
  io.mmu.mode  := Mux(log_inst_reg.valid, RAMMode.NOP, RAMMode.LW)
  io.mmu.wdata := 0.U

  // Feed to ID: valid only when no stall && no branch

  // Default output
  io.id.inst  := Const.NOP_INST
  io.id.excep := 0.U.asTypeOf(new Exception)

  when(!(stall || branch.valid)) {
    io.id.inst := inst.bits
    io.id.excep.pc := pc_reg
    io.id.excep.valid_inst := true.B
    when(pc_reg(1,0).orR) {
      io.id.excep.valid := true.B
      io.id.excep.value := pc_reg
      io.id.excep.code := Cause.InstAddressMisaligned
    }
    when(io.mmu.pageFault) {
      io.id.excep.valid := true.B
      io.id.excep.value := pc_reg
      io.id.excep.code := Cause.InstPageFault
    }
  }
}
