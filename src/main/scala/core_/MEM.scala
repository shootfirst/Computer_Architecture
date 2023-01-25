package core_

import chisel3._
import chisel3.util._

class MEM extends Module {
  val io = IO(new Bundle {
    val ex  = Flipped(new EX_MEM)
    //    class EX_MEM extends Bundle {
    //      val ramOp   = Output(new RAMOp_Output)
    //      val wrRegOp = Output(new WrRegOp)
    //      val wrCSROp = Output(new WrCSROp)
    //      val excep   = new Exception
    //      var ready   = Input(Bool())
    //    }
    val mmu = new MMUOp
    val csr = new MEM_CSR
    val reg = new WrRegOp

    val flush = Input(Bool())
  })

  // Lock input
  val ramOp_reg   = RegInit(0.U.asTypeOf(new RAMOp_Output))
  val wrRegOp_reg     = RegInit(0.U.asTypeOf(new WrRegOp))

  val wrCSROp_reg = RegInit(0.U.asTypeOf(new WrCSROp))

  val excep_reg   = RegInit(0.U.asTypeOf(new Exception))

  // Stall
  // mmu没有准备好之前一直stall
  val stall = io.mmu.mode =/= RAMMode.NOP && !io.mmu.ok
  io.ex.ready := !stall

  when(!stall) {
    ramOp_reg := io.ex.ramOp
    wrRegOp_reg := io.ex.wrRegOp
    wrCSROp_reg := io.ex.wrCSROp
    excep_reg := io.ex.excep

    when(io.csr.inter.valid && io.ex.excep.valid_inst) {
      excep_reg.valid := true.B
      excep_reg.code := io.csr.inter.bits
    }
//    printf("Pc:0x%x\n", io.ex.excep.pc)
//    printf("csr inter come? %d , valid? %d\n", io.csr.inter.valid, io.ex.excep.valid_inst);
  }

  // Default Output
  io.mmu.addr  := ramOp_reg.addr
  io.mmu.wdata := ramOp_reg.wdata
  io.mmu.mode  := ramOp_reg.mode

  io.reg.addr := wrRegOp_reg.addr
  io.reg.rdy  := true.B
  io.reg.data := Mux(RAMMode.isRead(ramOp_reg.mode), io.mmu.rdata, wrRegOp_reg.data)

  io.csr.wrCSROp := wrCSROp_reg
  io.csr.excep := excep_reg

  // New exception
  when(!excep_reg.valid) {

    // Address misaligned
    when(ramOp_reg.misaligned) {
      io.mmu.mode := RAMMode.NOP
      io.csr.excep.valid := true.B
      io.csr.excep.value := ramOp_reg.addr
      io.csr.excep.code := Mux(RAMMode.isRead(ramOp_reg.mode), Cause.LoadAddressMisaligned, Cause.StoreAddressMisaligned)
    }

    // PageFault
    when(io.mmu.pageFault) {
      io.csr.excep.valid := true.B
      io.csr.excep.value := ramOp_reg.addr
      io.csr.excep.code := Mux(RAMMode.isRead(ramOp_reg.mode), Cause.LoadPageFault, Cause.StorePageFault)
    }
  }.otherwise {
//    printf("[MEM] Input exception! Pc:0x%x Code:0x%x\n",excep.pc, excep.code)
  }

  // Handle Exception
  when(io.csr.excep.valid) {
    io.reg.addr := 0.U
    io.csr.wrCSROp.valid := false.B
    //printf("[MEM] ! Exception Pc: 0x%x Excep: %d\n", excepPc, excepEn)
  }
  when(excep_reg.valid) {
    // Avoid combinational loop
    io.mmu.mode := RAMMode.NOP
  }


  // Stall, output null
  when(stall) {
    io.csr.excep.valid_inst := false.B
    io.csr.excep.valid := false.B
    io.csr.wrCSROp.valid := false.B
    io.reg.addr := 0.U
  }

  // Handle flush
  when(io.flush) {
    excep_reg.valid := false.B
    excep_reg.valid_inst := false.B
    wrRegOp_reg.addr := 0.U
    wrCSROp_reg.valid := false.B
    ramOp_reg.mode := RAMMode.NOP
  }
}

