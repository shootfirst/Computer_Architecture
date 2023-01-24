package devices

import chisel3._
import core_._


class MockSerial(inputs_raw: String, printLog: Boolean = false) extends Module {
  val io = IO(Flipped(new RAMOp))

  val inputs = VecInit(inputs_raw.map(x => x.U(8.W)))
  val next_input_num_reg = RegInit(0.U(32.W))

  val canRead = next_input_num_reg =/= inputs.length.U
  val canWrite = true.B
  val status = (canWrite << 5.U) | canRead

  val mode_reg = RegNext(io.mode, init=0.U)
  val addr_reg = RegNext(io.addr, init=0.U)
  val wdata_reg = RegNext(io.wdata, init=0.U)

  val rdata = Wire(UInt(8.W))
  rdata := 0.U
  // test
  when((mode_reg === RAMMode.LB || mode_reg === RAMMode.LBU) && addr_reg(2,0) === 5.U) {
    rdata := status
  }
  // read
  when(RAMMode.isRead(mode_reg) && addr_reg(2,0) === 0.U) {
    when(canRead) {
      rdata := inputs(next_input_num_reg)
      next_input_num_reg := next_input_num_reg + 1.U
    }
    if (printLog)
      printf("[Serial] Read: (%d/%d) 0x%x, '%c'\n", next_input_num_reg, inputs_raw.length.U, rdata, rdata)
    else
      printf("%c", rdata)
  }
  // write
  when(mode_reg === RAMMode.SB && addr_reg(2,0) === 0.U) {
    if (printLog)
      printf("[Serial] Write: 0x%x, '%c'\n", wdata_reg(7,0), wdata_reg(7,0))
    else
      printf("%c", wdata_reg(7,0))
  }

  io.rdata := Mux(rdata(7) && mode_reg === RAMMode.LB, 0xffffff.U(24.W), 0.U(24.W)) ## rdata
  io.ok := mode_reg =/= RAMMode.NOP
}
