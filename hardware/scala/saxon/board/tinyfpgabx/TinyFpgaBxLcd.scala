package saxon.board.tinyfpgabx

import saxon._
import spinal.core._
import spinal.lib.com.uart.UartCtrlMemoryMappedConfig
import spinal.lib.generator._
import spinal.lib.io.{Gpio, InOutWrapper}
import saxon.board.blackice.IceStormInOutWrapper
import spinal.lib.com.spi.ddr.{SpiXdrMasterCtrl, SpiXdrParameter}
import spinal.lib.com.jtag.sim.JtagTcp
import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}
import saxon.board.blackice.peripheral._

class TinyFpgaBxLcdSystem extends BmbApbVexRiscvGenerator{
  //Add components
  val ramA = BmbOnChipRamGenerator(0x80000000l)
  val gpioA = Apb3GpioGenerator(0x00000)
  val uartA = Apb3UartGenerator(0x10000)
  val spiA = Apb3SpiGenerator(0x20000, xipOffset = 0x20000000)
  val machineTimer = Apb3MachineTimerGenerator(0x08000)
  val usbOff = TinyFpgaBxUsbOffGenerator()
  val lcd = Apb3Ili9341Generator(0x30000)

  ramA.dataWidth.load(32)

  //Interconnect specification
  val bridge = BmbBridgeGenerator()
  interconnect.addConnection(
    cpu.iBus -> List(bridge.bmb),
    cpu.dBus -> List(bridge.bmb),
    bridge.bmb -> List(ramA.bmb, peripheralBridge.input)
  )
}


class TinyFpgaBxLcd extends Generator{
  val clockCtrl = ClockDomainGenerator()
  clockCtrl.resetHoldDuration.load(255)
  clockCtrl.resetSynchronous.load(false)
  clockCtrl.powerOnReset.load(true)
  clockCtrl.clkFrequency.load(16 MHz)

  val system = new TinyFpgaBxLcdSystem
  system.onClockDomain(clockCtrl.clockDomain)

  val clocking = add task new Area{
    val clk = in Bool()
    val resetN = in Bool()

    clockCtrl.clock.load(clk)
    clockCtrl.reset.load(resetN)
  }
}

object TinyFpgaBxLcdSystem{
  def default(g : TinyFpgaBxLcdSystem, clockCtrl : ClockDomainGenerator) = g {
    import g._

    cpu.config.load(VexRiscvConfigs.xip.fast(0x20050000))
    cpu.enableJtag(clockCtrl)

    ramA.size.load(4 KiB)
    ramA.hexInit.load(null)
 
    gpioA.parameter load Gpio.Parameter(width = 1)

    // Configure uart
    uartA.parameter load UartCtrlMemoryMappedConfig(
      baudrate = 115200,
      txFifoDepth = 1,
      rxFifoDepth = 1
    )

    spiA.parameter load SpiXdrMasterCtrl.MemoryMappingParameters(
      SpiXdrMasterCtrl.Parameters(
        dataWidth = 8,
        timerWidth = 0,
        spi = SpiXdrParameter(
          dataWidth = 2,
          ioRate= 2,
          ssWidth = 1
        )
      ).addFullDuplex(id = 0, rate = 2).addHalfDuplex(id=1, rate=2, ddr=false, spiWidth=2),
      cmdFifoDepth = 64,
      rspFifoDepth = 64,
      cpolInit = false,
      cphaInit = false,
      modInit = 0,
      sclkToogleInit = 0,
      ssSetupInit = 0,
      ssHoldInit = 0,
      ssDisableInit = 0,
      xipConfigWritable = false,
      xipEnableInit = true,
      xipInstructionEnableInit = true,
      xipInstructionModInit = 0,
      xipAddressModInit = 0,
      xipDummyModInit = 0,
      xipPayloadModInit = 1,
      xipInstructionDataInit = 0x3B,
      xipDummyCountInit = 0,
      xipDummyDataInit = 0xFF
    )
    spiA.withXip.load(true)
    cpu.hardwareBreakpointCount.load(4)

    interconnect.addConnection(
      bridge.bmb -> List(spiA.bmb)
    )

    //Cut dBus address path
    interconnect.setConnector(bridge.bmb){(m,s) =>
      m.cmd >-> s.cmd
      m.rsp << s.rsp
    }

    g
  }
}


object TinyFpgaBxLcd {
  //Function used to configure the SoC
  def default(g : TinyFpgaBxLcd) = g{
    import g._
    TinyFpgaBxLcdSystem.default(system, clockCtrl)
    clockCtrl.resetSensitivity load(ResetSensitivity.LOW)
    system.spiA.inferSpiIce40()
    g
  }

  //Generate the SoC
  def main(args: Array[String]): Unit = {
    val report = SpinalRtlConfig.generateVerilog(IceStormInOutWrapper(default(new TinyFpgaBxLcd()).toComponent()))
    BspGenerator("TinyFpgaBxLcd", report.toplevel.generator, report.toplevel.generator.system.cpu.dBus)
  }
}

object TinyFpgaBxLcdSystemSim {
  import spinal.core.sim._

  def main(args: Array[String]): Unit = {

    val simConfig = SimConfig
    simConfig.allOptimisation
    simConfig.withWave
    simConfig.compile(new TinyFpgaBxLcdSystem(){
      val clockCtrl = ClockDomainGenerator()
      this.onClockDomain(clockCtrl.clockDomain)
      clockCtrl.makeExternal(ResetSensitivity.HIGH)
      clockCtrl.powerOnReset.load(true)
      clockCtrl.clkFrequency.load(16 MHz)

      TinyFpgaBxLcdSystem.default(this, clockCtrl)
    }.toComponent()).doSimUntilVoid("test", 42){dut =>
      val systemClkPeriod = (1e12/dut.clockCtrl.clkFrequency.toDouble).toLong
      val jtagClkPeriod = systemClkPeriod*4
      val uartBaudRate = 115200
      val uartBaudPeriod = (1e12/uartBaudRate).toLong

      val clockDomain = ClockDomain(dut.clockCtrl.clock, dut.clockCtrl.reset)
      clockDomain.forkStimulus(systemClkPeriod)


      val tcpJtag = JtagTcp(
        jtag = dut.cpu.jtag,
        jtagClkPeriod = jtagClkPeriod
      )

      val uartTx = UartDecoder(
        uartPin =  dut.uartA.uart.txd,
        baudPeriod = uartBaudPeriod
      )

      val uartRx = UartEncoder(
        uartPin = dut.uartA.uart.rxd,
        baudPeriod = uartBaudPeriod
      )

      val flash = new FlashModel(dut.spiA.phy, clockDomain)
      flash.loadBinary("software/standalone/lcdTexture/build/lcdTexture.bin", 0x050000)
    }
  }
}

