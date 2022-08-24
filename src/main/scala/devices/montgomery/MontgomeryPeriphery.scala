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

case class MontgomeryParams(
  baseAddress: BigInt,
  width: Int = 32,
  inputWidthCounterBit: Int = 16,
  block: Int = 8, // default to 256 bit mmm
  freqDiv: Int = 3,
  addPipe: Int = 1)

case object MontgomeryKey extends Field[Option[MontgomeryParams]](None)

trait HasMontgomeryIO extends BaseModule {
  val pWidth: Int
  val inputWidthCounterBit: Int // usually 16bit is enough

  val p = IO(Input(UInt(pWidth.W)))
  val pPrime = IO(Input(Bool()))
  val a = IO(Input(UInt(pWidth.W)))
  val b = IO(Input(UInt(pWidth.W)))
  val input_width = IO(Input(UInt(inputWidthCounterBit.W)))
  val valid = IO(Input(Bool())) // input valid
  val out = IO(Output(UInt(pWidth.W)))
  val out_valid = IO(Output(Bool())) // output valid
}

/** Specialize the generic USB to make it attachable to an TL interconnect. */
class MontgomeryTL(params: MontgomeryParams, busWidthBytes: Int)(implicit p: Parameters)
  extends Montgomery(params, busWidthBytes) with HasTLControlRegMap

trait CanHavePeripheryMontgomery { this: BaseSubsystem =>
  private val portName = "montgomery"

  val montgomery = p(MontgomeryKey) match {
    case Some(params) => {
      if (params.freqDiv > 1) {
        val freqDiv = params.freqDiv
        val freqMHz = pbus.dtsFrequency.get / freqDiv / 1000000
        val montgomreyClockParameters = ClockParameters(freqMHz = freqMHz.toDouble)
        val montgomeryClockDomainWrapper = LazyModule(new ClockSinkDomain(take = Some(montgomreyClockParameters)))
        val montgomery = montgomeryClockDomainWrapper { LazyModule(new MontgomeryTL(params, pbus.beatBytes)(p)) }

        pbus.coupleTo(portName) { bus =>
          val divider = LazyModule(new ClockDivider(freqDiv))
          montgomeryClockDomainWrapper.clockNode := divider.node := pbus.clockNode

          (montgomery.controlXing(AsynchronousCrossing())
            := TLFragmenter(pbus)
            := bus)
        }
        Some(montgomery)
      } else {
        val montgomery = LazyModule(new MontgomeryTL(params, pbus.beatBytes)(p))
        pbus.coupleTo(portName){ montgomery.controlXing(NoCrossing) :*= TLFragmenter(pbus) :*= _ }
        Some(montgomery)
      }
    }
    case None => None
  }
}

class WithMontgomery(baseAddress: BigInt, width: Int, block: Int, freqDiv: Int, addPipe: Int) extends Config((site, here, up) => {
  case MontgomeryKey => Some(MontgomeryParams(baseAddress = baseAddress, width = width, block = block, freqDiv = freqDiv, addPipe = addPipe))
})
