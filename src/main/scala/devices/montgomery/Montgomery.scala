package org.chipsalliance.rocketchip.blocks.devices.montgomery

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode.TruthTable
import chisel3.experimental.{IntParam, BaseModule, ChiselEnum}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.util.UIntIsOneOf

class MontgomeryMMIOChiselModule(val pWidth: Int, val inputWidthCounterBit: Int, val addPipe: Int) extends Module
  with HasMontgomeryIO
{
  val bPlusP = Reg(UInt((pWidth + 1).W))
  val invP = Reg(UInt((pWidth).W))
  val negP = Reg(UInt((pWidth + 2).W))
  val loopVarU = Reg(Bool())
  val index = Reg(UInt(inputWidthCounterBit.W))
  val nextT = Reg(UInt((pWidth + 2).W))

  // multicycle prefixadder
  require(addPipe > 0)
  val adder = Module(new DummyAdd(pWidth + 2, addPipe))
  val addStable = RegInit(0.U((pWidth + 2).W))
  // Control Path
  object StateType extends ChiselEnum {
    val s0 = Value("b0000001".U) // nextT = 0, loopVarU = a(0)b(0)pPrime, bPlusP = b + p
    // loop
    val s1 = Value("b0000010".U) // nextT + b
    val s2 = Value("b0000100".U) // nextT + p
    val s3 = Value("b0001000".U) // nextT + bPlusP
    // loop done
    val s4 = Value("b0010000".U) // index += 1, loopVarU = (nextT(0) + a(index)b(0))pPrime, nextT / 2
    val s5 = Value("b0100000".U) // nextT - p
    val s6 = Value("b1000000".U) // done
    val s7 = Value("b10000000".U) // nextT + 0
    val s8 = Value("b100000000".U) // calculate ~p
    val s9 = Value("b1000000000".U) // calculate ~p + 1
  }

  val state = RegInit(StateType.s0)
  val isAdd = (state.asUInt & "b1010101111".U).orR
  adder.valid := isAdd
  val addDoneNext = RegInit(false.B)
  addDoneNext := addDone
  lazy val addDone = if (addPipe != 0) Counter(valid && isAdd && (~addDoneNext), addPipe + 1)._2 else true.B
  val aIndexI = Reg(Bool())
  val iBreak = (index.asUInt >= inputWidth.asUInt)
  state := chisel3.util.experimental.decode
    .decoder(
      state.asUInt() ## addDoneNext ## valid ## iBreak ## loopVarU ## aIndexI, {
        val Y = "1"
        val N = "0"
        val DC = "?"
        def to(
          stateI:  String,
          addDone: String = DC,
          valid:   String = DC,
          iBreak:  String = DC,
          loopVarU:  String = DC,
          aIndexI:     String = DC
        )(stateO:  String
        ) = s"$stateI$addDone$valid$iBreak$loopVarU$aIndexI->$stateO"
        val s0 = "0000000001"
        val s1 = "0000000010"
        val s2 = "0000000100"
        val s3 = "0000001000"
        val s4 = "0000010000"
        val s5 = "0000100000"
        val s6 = "0001000000"
        val s7 = "0010000000"
        val s8 = "0100000000"
        val s9 = "1000000000"
        TruthTable.fromString(
          Seq(
            to(s0, valid = N)(s0),
            to(s0, valid = Y, addDone = N)(s0),
            to(s0, valid = Y, addDone = Y)(s8),
            to(s8)(s9),
            to(s9, addDone = N)(s9),
            to(s9, addDone = Y, aIndexI = Y, loopVarU = N)(s1),
            to(s9, addDone = Y, aIndexI = N, loopVarU = Y)(s2),
            to(s9, addDone = Y, aIndexI = Y, loopVarU = Y)(s3),
            to(s9, addDone = Y, aIndexI = N, loopVarU = N)(s7),
            to(s1, addDone = Y)(s4),
            to(s1, addDone = N)(s1),
            to(s2, addDone = Y)(s4),
            to(s2, addDone = N)(s2),
            to(s3, addDone = Y)(s4),
            to(s3, addDone = N)(s3),
            to(s7, addDone = Y)(s4),
            to(s7, addDone = N)(s7),
            to(s4, iBreak = Y)(s5),
            to(s4, iBreak = N, aIndexI = Y, loopVarU = N)(s1),
            to(s4, iBreak = N, aIndexI = N, loopVarU = Y)(s2),
            to(s4, iBreak = N, aIndexI = Y, loopVarU = Y)(s3),
            to(s4, iBreak = N, aIndexI = N, loopVarU = N)(s7),
            to(s5, addDone = Y)(s6),
            to(s5, addDone = N)(s5),
            to(s6, valid = N)(s0),
            to(s6, valid = Y)(s6),
            "??????????"
          ).mkString("\n")
        )
      }
    )
    .asTypeOf(StateType.Type())

  index := Mux1H(
    Map(
      state.asUInt()(0) -> 0.U,
      state.asUInt()(4) -> (index + 1.U),
      (state.asUInt & "b1111101110".U).orR -> index
    )
  )

  bPlusP := Mux(addDone & state.asUInt()(0), debounceAdd, bPlusP)

  loopVarU := Mux1H(
    Map(
      state.asUInt()(0) -> (a(0).asUInt & b(0).asUInt & pPrime.asUInt),
      (state.asUInt & "b0010001110".U).orR -> ((addStable(1) + (a(index + 1.U) & b(0))) & pPrime.asUInt),
      (state.asUInt & "b1101110000".U).orR -> loopVarU
    )
  )

  aIndexI := Mux1H(
    Map(
      state.asUInt()(0) -> a(0),
      (state.asUInt & "b0010001110".U).orR -> a(index + 1.U),
      (state.asUInt & "b1101110000".U).orR -> aIndexI
    )
  )

  nextT := Mux1H(
    Map(
      state.asUInt()(0) -> 0.U,
      state.asUInt()(4) -> (addStable >> 1),
      state.asUInt()(5) -> addStable,
      (state.asUInt & "b1111001110".U).orR -> nextT
    )
  )
  val TWithoutSubControl = Reg(UInt(1.W))
  val TWithoutSub = Reg(UInt((pWidth + 2).W))
  TWithoutSubControl := Mux(state.asUInt()(5), 0.U, 1.U)
  TWithoutSub := Mux(state.asUInt()(5) && (TWithoutSubControl === 1.U), nextT, TWithoutSub)
  invP := Mux(state.asUInt()(8), ~p, invP)
  negP := Mux(state.asUInt()(9), addStable, negP)

  adder.a := Mux1H(
    Map(
      state.asUInt()(0) -> p,
      state.asUInt()(9) -> 1.U,
      (state.asUInt & "b0111111110".U).orR -> nextT
    )
  )
  adder.b := Mux1H(
    Map(
      (state.asUInt & "b0100000011".U).orR -> b,
      state.asUInt()(9) -> Cat(3.U, invP),
      state.asUInt()(2) -> p,
      state.asUInt()(3) -> bPlusP,
      state.asUInt()(7) -> 0.U,
      state.asUInt()(5) -> negP
    )
  )
  lazy val debounceAdd = Mux(addDone, adder.z, 0.U)
  addStable := Mux(addDone, debounceAdd, addStable)

  // output
  out := Mux(nextT.head(1).asBool, TWithoutSub, nextT)
  outValid := state.asUInt()(6)
}

class DummyAdd(width: Int, pipe: Int) extends Module {
  val valid = IO(Input(Bool()))
  val a = IO(Input(UInt(width.W)))
  val b = IO(Input(UInt(width.W)))
  val z = IO(Output(UInt(width.W)))
  val rs = Seq.fill(pipe + 1) { Wire(chiselTypeOf(z)) }
  rs.zipWithIndex.foreach {
    case (r, i) =>
      if (i == 0) r := Mux(valid, a + b, 0.U) else r := Mux(valid, RegNext(rs(i - 1)), 0.U)
  }
  z := rs.last
}

abstract class Montgomery(val params: MontgomeryParams, busWidthBytes: Int)
          (implicit p: Parameters)
    extends RegisterRouter(
      RegisterRouterParams(
        name = "montgomery",
        compat = Seq("plct-caat,montgomery"),
        base = params.baseAddress,
        beatBytes = busWidthBytes)) {

  lazy val module = new LazyModuleImp(this) {

  val block = params.block
  // use q for p here since p is used by Parameters
  // to support 4096-bit,  a and b and q should read 32bit per cycle (128 cycles)
  // to support 256-bit,  a and b and q should read 32bit per cycle (8 cycles)
  val queueSize = 1
  val q = Module(new Queue(UInt(params.width.W), queueSize))
  val pPrime = Reg(Bool())
  val a = Module(new Queue(UInt(params.width.W), queueSize))
  val b = Module(new Queue(UInt(params.width.W), queueSize))
  val inputWidth = Reg(UInt(params.inputWidthCounterBit.W))
  val out = Module(new Queue(UInt(params.width.W), queueSize))
  // 0 for idle, 1 for reset, 2 for ready
  // reset not implemented yet
  val control = RegInit(0.U(2.W))
  val status = Wire(UInt(1.W))

  val length = params.width * block // mmm pWidth (e.g. 4096, 2048, 256)
  val impl = Module(new MontgomeryMMIOChiselModule(length, params.inputWidthCounterBit, params.addPipe))

  val realInputBlock = RegInit(0.U((params.inputWidthCounterBit).W))
  realInputBlock := Mux(control === 2.U, (inputWidth + 32.U) >> 5.U, realInputBlock) // should add with 32 but not 31, because inputWidth = original lenght - 1

  val qToImpl = Reg(Vec(block, UInt(32.W)))
  val aToImpl = Reg(Vec(block, UInt(32.W)))
  val bToImpl = Reg(Vec(block, UInt(32.W)))
  val validToImpl = RegInit(0.U(1.W))
  impl.p := qToImpl.asUInt
  impl.pPrime := pPrime
  impl.a := aToImpl.asUInt
  impl.b := bToImpl.asUInt
  impl.inputWidth := inputWidth.asUInt
  impl.valid := validToImpl
  
  val inputCounterA = Counter(0 to block, (control === 2.U) && (a.io.deq.valid), (control === 0.U))._1
  val inputCounterB = Counter(0 to block, (control === 2.U) && (b.io.deq.valid), (control === 0.U))._1
  val inputCounterQ = Counter(0 to block, (control === 2.U) && (q.io.deq.valid), (control === 0.U))._1
  validToImpl := Mux((inputCounterA >= realInputBlock.asUInt)
                && (inputCounterB >= realInputBlock.asUInt)
                && (inputCounterQ >= realInputBlock.asUInt)
                && (control === 2.U), 1.U, 0.U)
  q.io.deq.ready := control === 2.U
  a.io.deq.ready := control === 2.U
  b.io.deq.ready := control === 2.U

  qToImpl.zipWithIndex.foreach {
    case (row, i) =>
      row := Mux((control === 2.U)
              && (q.io.deq.valid)
              && (i.asUInt === inputCounterQ.asUInt),
                q.io.deq.bits,
                Mux(i.asUInt < inputCounterQ.asUInt, row, 0.U)
      )
  } // make sure there is only i-bit valid data, the other bits are all 0
  aToImpl(inputCounterA) := Mux((control === 2.U) && (a.io.deq.valid), a.io.deq.bits, aToImpl(inputCounterA)) // aToImpl doesn't require to set invalid bits to 0 because the mmm algorithm only use some specific bits of aToImpl
  bToImpl.zipWithIndex.foreach {
    case (row, i) =>
      row := Mux((control === 2.U)
              && (b.io.deq.valid)
              && (i.asUInt === inputCounterB.asUInt),
                b.io.deq.bits,
                Mux(i.asUInt < inputCounterB.asUInt, row, 0.U)
      )
  } // the same with qToImpl

  // Manage Output
  val outValid = Reg(UInt(1.W))
  val out32 = VecInit(impl.out.asBools().grouped(32).map(VecInit(_).asUInt()).toSeq)

  val outCounterEnable = Reg(UInt(1.W)) // make sure the counter only run once
  val outCounter = Counter(0 to block-1, (outCounterEnable === 1.U) && (outValid === 1.U), (control === 0.U))._1
  outCounterEnable := out.io.enq.fire
  outValid := Mux((outCounter === realInputBlock.asUInt-1.U) || (control === 0.U),  Mux(out.io.enq.fire, 0.U, outValid), impl.outValid)

  out.io.enq.bits := out32(outCounter)
  out.io.enq.valid := outValid

  status := outValid & (control === 2.U)

  // regmap
  regmap(
    0x00 -> Seq(
      RegField.r(1, status)), // a read-only register capturing current status
    0x04 -> Seq(
      RegField.w(2, control)),
    0x08 -> Seq(
      RegField.w(1, pPrime)),
    0x0C -> Seq(
      RegField.w(params.width, q.io.enq)), // a plain, write-only register
    0x10 -> Seq(
      RegField.w(params.width, a.io.enq)),
    0x14 -> Seq(
      RegField.w(params.width, b.io.enq)),
    0x18 -> Seq(
      RegField.w(params.inputWidthCounterBit, inputWidth)),
    0x1C -> Seq(
      RegField.r(params.width, out.io.deq)),
  )
  }
}
