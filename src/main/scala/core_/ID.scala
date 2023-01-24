package core_

import chisel3._
import chisel3.util._

class IDState extends Bundle {
  val bt = Output(UInt(5.W))
  val l = Output(Bool())
  val type_ = Output(UInt(3.W))
  val opt = Output(UInt(5.W))
  val pc = Output(UInt(5.W))
  val imm = Output(SInt(32.W))

}

class ID extends Module {
  val io = IO(new Bundle {
    val iff = Flipped(new IF_ID)  // naming conflict if use `if`
    val reg = new ID_Reg
    val ex = new ID_EX
    val csr = new ID_CSR
  
    val flush = Input(Bool())

    // forwarding
    val exWrRegOp = Flipped(new WrRegOp)
    val memWrRegOp = Flipped(new WrRegOp)

    val exWrCSROp = Flipped(new WrCSROp)
    val memWrCSROp = Flipped(new WrCSROp)

    //output log
    val debug = new IDState()
  })

  // Registers
  val inst_reg  = RegInit(Const.NOP_INST)
  val excep_reg = RegInit(0.U.asTypeOf(new Exception))
  val pc    = excep_reg.pc
  val fenceICnt_reg = RegInit(0.U(2.W)) // FENCE.I -> NOP * 4

  // If ID is stalling, the current instruction is not executed in this cycle.
  //    (i.e. current instruction is `flushed')
  // Therefore, on the next cycle, ID must execute the same instruction.
  // However when IF sees a `stall', it simply gives out a `nop'.
  // As a result, ID should not update (receive from IF) its instruction
  //  when stalled.
  val stall = Wire(Bool()) // Assign later
  val flush = io.flush
  when(flush) {
    inst_reg  := Const.NOP_INST
    excep_reg := 0.U.asTypeOf(new Exception)
  }.elsewhen(stall) {
    inst_reg  := inst_reg
    excep_reg := excep_reg
  }.otherwise {
    // NOTE: No need to check branch now, IF already handle that.
    inst_reg  := io.iff.inst
    excep_reg := io.iff.excep
  }

  // Decode instruction
  val decRes   = ListLookup(inst_reg, DecTable.defaultDec, DecTable.decMap)
  val instType = decRes(DecTable.TYPE)
  val rs1Addr  = inst_reg(19, 15)
  val rs2Addr  = inst_reg(24, 20)
  val rdAddr   = inst_reg(11, 7)
  val csrAddr  = inst_reg(31,20)
  val imm      = Wire(SInt(32.W)) // Assign later

  // Read from RegFile
  io.reg.read1.addr := rs1Addr
  io.reg.read2.addr := rs2Addr
  io.csr.addr := csrAddr

  // Final register value (include forwarding)
  val rs1Val = PriorityMux(Seq(
    (rs1Addr === 0.U,                 0.U),                // reading x0 always gives 0
    (rs1Addr === io.exWrRegOp.addr,   io.exWrRegOp.data),  // forwarding from EX
    (rs1Addr === io.memWrRegOp.addr,  io.memWrRegOp.data), // forwarding from MEM
    (true.B,                          io.reg.read1.data))) // from the register file
  val rs2Val = PriorityMux(Seq(
    (rs2Addr === 0.U,                 0.U),                // reading x0 always gives 0
    (rs2Addr === io.exWrRegOp.addr,   io.exWrRegOp.data),  // forwarding from EX
    (rs2Addr === io.memWrRegOp.addr,  io.memWrRegOp.data), // forwarding from MEM
    (true.B,                          io.reg.read2.data))) // from the register file
  val csrVal = PriorityMux(Seq(
    (csrAddr === io.exWrCSROp.addr && io.exWrCSROp.valid,   io.exWrCSROp.data),
    (csrAddr === io.memWrCSROp.addr && io.memWrCSROp.valid, io.memWrCSROp.data),
    (true.B,                                                io.csr.rdata)))

  // Debug
  val d = io.debug
  d.pc := pc
  d.imm := imm
  d.bt := 0.U
  d.l := false.B
  d.type_ := instType
  d.opt := decRes(DecTable.OPT)

  // Default output
  io.iff.branch := 0.U.asTypeOf(Valid(UInt(32.W)))
  io.ex.aluOp := 0.U.asTypeOf(new ALUOp)
  io.ex.aluOp.opt := decRes(DecTable.OPT)
  io.ex.wrCSROp := 0.U.asTypeOf(new WrCSROp)
  io.ex.wrRegOp := 0.U.asTypeOf(new WrRegOp)
  io.ex.excep := excep_reg
  io.ex.excep.valid_inst := excep_reg.valid_inst && !stall
  io.ex.store_data := 0.U
  imm := 0.S
  // OPTIMIZE: Better way to set io.ex = 0 ?

  // deal with different kind inst

  // read-after-load data hazard
  val instTypesUsingRs1 = Seq(InstType.R, InstType.I, InstType.S, InstType.B)
  val instTypesUsingRs2 = Seq(InstType.R, InstType.S, InstType.B)
  val rs1Hazard = (rs1Addr === io.exWrRegOp.addr) &&
    instTypesUsingRs1.map(x => x === instType).reduce(_ || _)
  val rs2Hazard = (rs2Addr === io.exWrRegOp.addr) &&
    instTypesUsingRs2.map(x => x === instType).reduce(_ || _)
  stall := (!io.exWrRegOp.rdy) && (io.exWrRegOp.addr.orR) && (rs1Hazard || rs2Hazard) || !io.ex.ready

  // Check whether the jump target is misaligned.
  // If true, raise an exception, otherwise set branch.
  def checkAndJump(target: UInt): Unit = {
    when(target(1,0).orR) { // misaligned
      when(!excep_reg.valid) {
        io.ex.excep.valid := true.B
        io.ex.excep.value := target
        io.ex.excep.code := Cause.InstAddressMisaligned
      }
    }.otherwise {
      io.iff.branch.bits := target
      io.iff.branch.valid := true.B
    }
  }

  when (stall) {
    // flush current instruction
    io.ex.wrRegOp.addr := 0.U             // don't write registers
    io.ex.aluOp.opt := OptCode.ADD    // don't write memory
    io.iff.branch.valid := false.B // don't branch
    io.iff.ready := false.B     // tell IF not to advance
  }.otherwise {
    io.iff.ready := true.B
//    printf("Pc: 0x%x Inst:0x%x type: %d\n",pc, inst, instType)
    switch(instType) {
      is(InstType.R) {
        io.ex.aluOp.rd1 := rs1Val
        io.ex.aluOp.rd2 := rs2Val
        io.ex.wrRegOp.addr := rdAddr
      }
      is(InstType.I) {
        imm := inst_reg(31,20).asSInt
        io.ex.aluOp.rd1 := rs1Val
        io.ex.aluOp.rd2 := imm.asUInt
        io.ex.wrRegOp.addr := rdAddr

        when(decRes(DecTable.OPT) === OptCode.JALR) {
          checkAndJump((imm.asUInt + rs1Val) & (~ 1.U(32.W)))
          io.ex.aluOp.rd1 := pc
          io.ex.aluOp.rd2 := 4.U
          io.ex.aluOp.opt := OptCode.ADD
        }
      }
      is(InstType.S) {
        imm := Cat(inst_reg(31,25), inst_reg(11,7)).asSInt
        io.ex.aluOp.rd1 := rs1Val
        io.ex.aluOp.rd2 := imm.asUInt
        io.ex.store_data := rs2Val
      }
      is(InstType.B) {
        val bt = decRes(DecTable.OPT)
        val l = Mux(bt(0), rs1Val < rs2Val, rs1Val.asSInt < rs2Val.asSInt)
        val g = Mux(bt(0), rs1Val > rs2Val, rs1Val.asSInt > rs2Val.asSInt)
        val e = rs1Val === rs2Val
        val jump = (l & bt(3)) | (e & bt(2)) | (g & bt(1))

        imm := Cat( inst_reg(31), inst_reg(7), inst_reg(30,25), inst_reg(11,8), 0.U(1.W)).asSInt
        when(jump) {
          checkAndJump(pc + imm.asUInt)
        }

        d.bt := bt
        d.l := l
      }
      is(InstType.U) {
        imm := (inst_reg & "h_fffff000".U).asSInt
        io.ex.aluOp.rd1 := imm.asUInt
        val ut = decRes(DecTable.OPT)
        io.ex.aluOp.rd2 := Mux(ut(0), pc, 0.U)
        io.ex.aluOp.opt   := OptCode.ADD
        io.ex.wrRegOp.addr := rdAddr
      }
      is(InstType.J) {
        imm := Cat(inst_reg(31), inst_reg(19,12), inst_reg(20), inst_reg(30,21), 0.U(1.W)).asSInt
        checkAndJump(pc + imm.asUInt)
        io.ex.aluOp.rd1 := pc
        io.ex.aluOp.rd2 := 4.U
        io.ex.aluOp.opt := OptCode.ADD //not necessary
        io.ex.wrRegOp.addr := rdAddr
      }
      is(InstType.SYS) {
        val fct3 = inst_reg(14,12)

        when(fct3.orR) {  // CSR inst. Calculate new value here.
          val mode = fct3(1,0)
          val rsVal = Mux(fct3(2), rs1Addr, rs1Val)
          val newVal = MuxLookup(mode, 0.U, Seq(
            (CSRMODE.RW, rsVal),
            (CSRMODE.RS, csrVal | rsVal),
            (CSRMODE.RC, csrVal & ~rsVal)
          ))
          io.ex.wrCSROp.valid := true.B
          io.ex.wrCSROp.addr := csrAddr
          io.ex.wrCSROp.data := newVal
          io.ex.wrRegOp.addr := rdAddr
          io.ex.aluOp.rd1    := csrVal
        }
        .elsewhen(inst_reg(31,25) === SYS_INST_P1.SFENCE_VMA) {
          when(!excep_reg.valid) {
            io.ex.excep.valid := true.B
            io.ex.excep.value := rs1Val
            io.ex.excep.code := Mux(io.csr.prv >= Priv.S,
              Mux(rs1Addr === 0.U, Cause.SFenceAll, Cause.SFenceOne),
              Cause.IllegalInstruction)
          }
        }
        .otherwise {
          val inst_p2 = inst_reg(24,20)
          when(!excep_reg.valid) {
            switch(inst_p2) {
              is(SYS_INST_P2.ECALL) {
                io.ex.excep.valid := true.B
                io.ex.excep.code := Cause.ecallX(io.csr.prv)
              }
              is(SYS_INST_P2.EBREAK) {
                io.ex.excep.valid := true.B
                io.ex.excep.code := Cause.BreakPoint
              }
              is(SYS_INST_P2.xRET) {
                val prv = inst_reg(29,28)
                io.ex.excep.valid := true.B
                io.ex.excep.code := Mux(io.csr.prv >= prv,
                  Cause.xRet(prv),
                  Cause.IllegalInstruction)
              }
            }
          }
        }
      }
      is(InstType.FENCE) {
        when(inst_reg(14,12) === "b001".U) { // FENCE.I
          when(fenceICnt_reg === 3.U) {
            fenceICnt_reg := 0.U
          }
          .otherwise {
            fenceICnt_reg := fenceICnt_reg + 1.U
            io.iff.branch.valid := true.B
            io.iff.branch.bits := pc
          }
        }
        // deal FENCE as NOP
      }
      is(InstType.BAD) {
        when(!excep_reg.valid) {
          io.ex.excep.valid := true.B
          io.ex.excep.value := inst_reg
          io.ex.excep.code := Cause.IllegalInstruction
        }
      }
    }
  }

}
