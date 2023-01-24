package core_

import chisel3._
import chisel3.util._
import OptCode._

class EX extends Module {
  val io = IO(new Bundle {
    val id  = Flipped(new ID_EX)
    val mem = new EX_MEM

    val flush = Input(Bool())
  })

  //------------------- ALU ----------------------

  // Lock input
  val alu_reg = RegNext(io.id.aluOp, init=0.U.asTypeOf(new ALUOp))

  // Alias
  val a   = alu_reg.rd1
  val b   = alu_reg.rd2
  val opt = alu_reg.opt
  val shamt = b(4, 0)

  // NOTICE: SLL,SRL,SRA only use lower 5 bits of b
  val aluRes = MuxLookup(opt,
    a + b,
    Seq(
      ADD -> (a + b),
      SUB -> (a - b),
      SLT -> Mux(a.asSInt < b.asSInt, 1.U, 0.U),
      SLTU -> Mux(a < b, 1.U, 0.U),
      XOR -> (a ^ b),
      OR -> (a | b),
      AND -> (a & b),
      SLL -> (a << shamt),
      SRL -> (a >> shamt),
      SRA -> (a.asSInt >> shamt).asUInt,

      MUL -> (a * b)(31,0),
      MULH ->( a.asSInt * b.asSInt)(63,32).asUInt,
      MULHU -> (a * b)(63,32),
      MULHSU -> (a.asSInt * b)(63,32).asUInt,
      DIV -> (a.asSInt / b.asSInt).asUInt,
      DIVU -> a / b,
      REM  -> (a.asSInt % b.asSInt).asUInt,
      REMU -> a % b
      // not necessary, all rest (a+b)
    )
  )

  //-------------- Reg & Ram Op ------------------

  // Lock input
  val wregAddr_reg   = RegNext(io.id.wrRegOp.addr, init=0.U(5.W))
  val store_data_reg = RegNext(io.id.store_data,   init=0.U(32.W))

  io.mem.wrRegOp.addr := wregAddr_reg
  io.mem.wrRegOp.data := aluRes
  io.mem.wrRegOp.rdy  := (opt & OptCode.LW) =/= OptCode.LW

  io.mem.ramOp.addr := aluRes
  io.mem.ramOp.mode := MuxLookup(opt, RAMMode.NOP, Seq(
    OptCode.LW  -> RAMMode.LW,
    OptCode.LB  -> RAMMode.LB,
    OptCode.LBU -> RAMMode.LBU,
    OptCode.LH  -> RAMMode.LH,
    OptCode.LHU -> RAMMode.LHU,
    OptCode.SW  -> RAMMode.SW,
    OptCode.SH  -> RAMMode.SH,
    OptCode.SB  -> RAMMode.SB
  ))
  io.mem.ramOp.wdata := store_data_reg

  //------------------- CSR ----------------------

  val excep_reg   = RegNext(io.id.excep)
  val wrCSROp_reg = RegNext(io.id.wrCSROp)
  io.mem.excep   := excep_reg
  io.mem.wrCSROp := wrCSROp_reg

  //----------------- status ---------------------

  val countdown_reg = RegInit(0.U(3.W))
  val flush = io.flush
  // mem还在读写内存或者乘除指令
  val stall = !io.mem.ready || countdown_reg =/= 0.U

  io.id.ready := !stall

  // Wait 7 cycles for mul/div
  when(!stall && io.id.aluOp.opt >= 11.U && io.id.aluOp.opt <= 18.U) {
    countdown_reg := 7.U
  }.elsewhen(countdown_reg =/= 0.U) {
    countdown_reg := countdown_reg - 1.U
  }.otherwise {
    countdown_reg := 0.U
  }

  when(stall) {
    alu_reg := alu_reg
    wregAddr_reg := wregAddr_reg
    store_data_reg := store_data_reg
    excep_reg := excep_reg
    wrCSROp_reg := wrCSROp_reg
  }

  when(flush) {
    opt := OptCode.ADD
    wregAddr_reg := 0.U
    wrCSROp_reg.valid := false.B
    excep_reg.valid := false.B
    excep_reg.valid_inst := false.B
  }

}
