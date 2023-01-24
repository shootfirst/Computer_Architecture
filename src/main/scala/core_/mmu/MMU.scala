package core_.mmu

import chisel3._
import chisel3.util._
import core_._
import devices.NullDev

class MMU extends Module {
  val io = IO(new Bundle {
    val iff = Flipped(new MMUOp())
    val mem = Flipped(new MMUOp())
    val csr = Flipped(new CSR_MMU())

    val dev = new Core_IO
  })

  val ptw = Module(new PTW())
  val tlb = Module(new TLB(5))

  // Whether all IO finished at the next rising edge.
  // Only when `ready = 1`, satp change or TLB invalidation can be accepted at next cycle.
  val ready = io.iff.ready && io.mem.ready

  val csr_reg = RegInit(0.U.asTypeOf(new CSR_MMU()))
  when(ready) {
    csr_reg := io.csr
  }

  // TLB can only be flushed when MMU ready.
  // But flush request from CSR may comes at any time.
  // So we need to log flush request when not ready.
  val log_flush_reg = RegInit(0.U.asTypeOf(new FlushOp))
  when(io.csr.flush.valid) {
    log_flush_reg := io.csr.flush
  }
  // Final flush op on ready
  val flush = Mux(io.csr.flush.valid, io.csr.flush, log_flush_reg)

  // Enable MMU?
  val enable = csr_reg.satp(31) && csr_reg.priv =/= Priv.M
  tlb.reset := !enable
  ptw.io.root := csr_reg.satp(19, 0).asTypeOf(new PN)

  val null_device = Module(new NullDev)
  ptw.io.mem <> null_device.io

  // TLB query path
  def bindTLB(vop: MMUOp, pop: RAMOp, tlb: TLBQuery): Unit = {
    // vop.in -> TLB.in
    tlb.req.valid := vop.mode =/= RAMMode.NOP
    tlb.req.bits := PN.fromAddr(vop.addr)
    // vop.in + TLB.out -> pop.in
    pop.mode := vop.mode
    pop.wdata := vop.wdata
    pop.addr := tlb.rsp.bits.ppn.toAddr(vop.addr)
    // pop.out -> vop.out
    vop.ok := pop.ok && tlb.rsp.valid
    vop.rdata := pop.rdata

    when(tlb.miss) {
      pop.mode := 0.U
      vop.ok := false.B
    }.elsewhen(vop.pageFault) {
      pop.mode := 0.U
      pop.wdata := 0.U
      pop.addr := 0.U
      vop.ok := true.B
    }
  }
  bindTLB(io.iff, io.dev.if_, tlb.io.query)
  bindTLB(io.mem, io.dev.mem, tlb.io.query2)

  // Handle page fault
  { // IF
    val rsp = tlb.io.query.rsp
    val e_exec = !(rsp.bits.V && rsp.bits.X)
    val e_user = csr_reg.priv === Priv.U && !rsp.bits.U
    io.iff.pageFault := enable && rsp.valid && io.iff.mode =/= RAMMode.NOP && (e_exec || e_user)
  }
  { // MEM
    val rsp = tlb.io.query2.rsp
    val e_read  = RAMMode.isRead(io.mem.mode) && !(rsp.bits.V && (rsp.bits.R || rsp.bits.X && csr_reg.mxr))
    val e_write = RAMMode.isWrite(io.mem.mode) && !(rsp.bits.V && rsp.bits.W)
    val e_user  = csr_reg.priv === Priv.U && !rsp.bits.U
    val e_sum   = csr_reg.priv === Priv.S && !csr_reg.sum && rsp.bits.U
    io.mem.pageFault := enable && rsp.valid && io.mem.mode =/= RAMMode.NOP && (e_read || e_write || e_user || e_sum)
  }

  // Detect TLB miss and refill
  //
  // Status:
  // - Ready:
  //   - 2 IO path is available
  //   - If TLB miss is detected, and the other IO is finished,
  //     send request to PTW, then go to Walking.
  // - Walking:
  //   - 2 IO path is blocked. PTW is connected to IF port.
  //   - Wait for PTW response, then refill TLB, go to Ready.
  val sReady :: sWalking :: Nil = Enum(2)
  val status_reg = RegInit(sReady)
  val ptw_vpn_reg = RegInit(0.U.asTypeOf(new PN)) // Stored at Ready->Walking

  // Default output
  ptw.io.req.valid := false.B
  ptw.io.req.bits := 0.U.asTypeOf(new PN)
  ptw.io.rsp.ready := false.B
  tlb.io.modify := 0.U.asTypeOf(new TLBModify)

  switch(status_reg) {
    is(sReady) {
      def detect(tlb: TLBQuery, otherReady: Bool): Unit = {
        when(tlb.miss && otherReady && ptw.io.req.ready) {
          ptw.io.req.valid := true.B
          ptw.io.req.bits := tlb.req.bits
          status_reg := sWalking
          ptw_vpn_reg := tlb.req.bits
        }
      }
      detect(tlb.io.query, io.dev.mem.ready)
      detect(tlb.io.query2, io.dev.if_.ready)

      // Flush TLB
      when(ready) {
        when(flush.one) {
          tlb.io.modify.mode := TLBOp.Remove
          tlb.io.modify.vpn := PN.fromAddr(flush.addr)
        }.elsewhen(flush.all) {
          tlb.io.modify.mode := TLBOp.Clear
        }
        // Clear flush log
        log_flush_reg := 0.U.asTypeOf(new FlushOp)
      }
    }
    is(sWalking) {
      // Insert Reg to avoid combinational loop
      // Better way to do this ?
      io.dev.if_.addr := RegNext(ptw.io.mem.addr)
      io.dev.if_.mode := RegNext(ptw.io.mem.mode)
      io.dev.if_.wdata := 0.U
      ptw.io.mem.rdata := io.dev.if_.rdata
      ptw.io.mem.ok := io.dev.if_.ok

      io.iff.ok := false.B
      io.dev.mem.mode := 0.U
      io.mem.ok := false.B
      ptw.io.rsp.ready := true.B
      when(ptw.io.rsp.valid) {
        tlb.io.modify.mode := TLBOp.Insert
        tlb.io.modify.vpn := ptw_vpn_reg
        tlb.io.modify.pte := ptw.io.rsp.bits
        status_reg := sReady
      }
    }
  }

  // To force update value in wave ...
  val _dummy1_reg = RegNext(ptw.io.req.valid)
}
