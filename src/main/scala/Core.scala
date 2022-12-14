import chisel3._
import chisel3.util._
import chisel3.experimental.Analog
import Core._

object Core{
  val Nil = 0.U
  val Arithmetic = 1.U
  val ImmidiateArithmetic = 2.U
  val MemoryI = 3.U
  val Conditional = 4.U
  val FirRead = 5.U
  val vArithmetic = 6.U
  val vImmidiateArithmetic = 7.U
  val vMemoryI = 8.U

}

class Core(Program: String, Lanes: Int, VectorRegisters: Int, VectorRegisterLength: Int, Memsize: Int) extends Module {
  val io = IO(new Bundle {
    val WaveIn = Input(UInt(24.W))
    val WaveOut = Output(UInt(24.W))

    val MemPort = new MemPort(VectorRegisterLength)
    val MemTaken = Input(Bool())
  })

  // Initializing pipeline 

  val FetchStage = Module(new FetchStage(Program))
  val DecodeStage = Module(new DecodeStage(VectorRegisterLength))
  val ExecuteStage = Module(new ExecuteStage(Lanes, VectorRegisters, VectorRegisterLength, Memsize))

  //Initializing Registers
  //x = register bank
  // x0 = 0
  // x1 = PC
  // x2 = Vector length
  // x3 = input
  // x4 = output
  // x5 - x31 = temp
 
  val x = Reg(Vec(32,UInt(24.W)))

  val vx = Reg(Vec(VectorRegisters,Vec(VectorRegisterLength,UInt(24.W))))

  val InstructionDelay = RegInit(0.U(24.W))


  x(0) := 0.U(VectorRegisterLength.W)
  x(3) := io.WaveIn
  io.WaveOut := x(4)
  
  // Default
  // asserting fetch decode to be false by default

  FetchStage.io.Stall := false.B
  FetchStage.io.Clear := false.B
  FetchStage.io.WriteEn := false.B
  FetchStage.io.WriteData := 0.U

  DecodeStage.io.Stall := false.B
  DecodeStage.io.Clear := false.B

  ExecuteStage.io.MemPort <> io.MemPort
  ExecuteStage.io.Clear := false.B
  ExecuteStage.io.x := x
  ExecuteStage.io.MemTaken := io.MemTaken

  ExecuteStage.vio.vx := vx
  ExecuteStage.vio.len := x(2)

  // Processor

  // Instruction Fetch

  FetchStage.In.PC := x(1)
  FetchStage.io.Stall := ExecuteStage.io.Stall 

  //InstructionDelay := FetchStage.Out.Instruction

  DecodeStage.In := FetchStage.Out
  DecodeStage.io.Stall := ExecuteStage.io.Stall

  when(!ExecuteStage.io.Stall){
    x(1) := x(1) + 1.U
  }

  // Instruction Decode

  // Execute
  // sends instruction from decode to execute

  ExecuteStage.In := DecodeStage.Out
  ExecuteStage.VectorIn := DecodeStage.VectorOut

  // Writeback
  // Execute stage actions


  switch(ExecuteStage.Out.WritebackMode){
      is(Arithmetic){
        x(ExecuteStage.Out.WritebackRegister) := ExecuteStage.Out.ALUOut

        // In case of jump, the pipeline is cleared

        when(ExecuteStage.Out.WritebackRegister === 1.U){
          FetchStage.io.Clear := true.B
          DecodeStage.io.Clear := true.B
          ExecuteStage.io.Clear := true.B
        }
      }
      is(MemoryI){
        //x(ExecuteStage.Out.WritebackRegister) := io.MemPort.ReadData(0)
        x(ExecuteStage.Out.WritebackRegister) := ExecuteStage.Out.MemOut
      }
      is(Conditional){
        x(1) := ExecuteStage.Out.JumpValue
        FetchStage.io.Clear := true.B
        DecodeStage.io.Clear := true.B
        ExecuteStage.io.Clear := true.B
      }
      is(vArithmetic){
        when(ExecuteStage.Out.readValid.asBool){
          vx(ExecuteStage.Out.WritebackRegister) := ExecuteStage.Out.VALUOut
        }
      }
      is(vMemoryI){
        
        when(io.MemPort.ReadValid){
          vx(ExecuteStage.Out.WritebackRegister) := io.MemPort.ReadData
        }
      }
    }

}
