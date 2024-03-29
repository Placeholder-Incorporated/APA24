import chisel3._
import chisel3.util._
import chisel3.experimental.Analog
import chisel3.util.experimental.loadMemoryFromFile
import scala.math
import CacheStates._

object CacheStates {
  val Writeback = "h1".U(4.W)
  val CacheWrite = "h2".U(4.W)
  val ReadWriteback = "h3".U(4.W)
  val CacheRead = "h4".U(4.W)
  //Vector stuff 
  val VectorRead = "h5".U(4.W)
  val VectorWrite = "h6".U(4.W)
  val WritebackScan = "h7".U(4.W)
}

class WriteBackCache(Bitsize: Int, VectorRegisterLength: Int) extends Module {
  val io = IO(new Bundle {
    val MemPort = Flipped(new MemPort(VectorRegisterLength))
    val EXT = new MemPort(VectorRegisterLength)
  })

  val StateReg = RegInit(0.U(4.W))

  val PointerReg = RegInit(0.U(6.W))

  // Module Definitions

  for(j <- 0 until VectorRegisterLength){
    io.MemPort.ReadData(j) := 0.U
  }

  io.MemPort.Completed := false.B
  io.MemPort.ReadValid := false.B
  io.MemPort.Ready := false.B


  for(i <- 0 until VectorRegisterLength){
    io.EXT.WriteData(i) := 0.U
  }

  io.EXT.Address := 0.U
  io.EXT.Enable := false.B
  io.EXT.WriteEn := false.B
  io.EXT.Len := 0.U


  var Size = 24 + (24-Bitsize) + 1 

  val Memory = SyncReadMem(scala.math.pow(2,Bitsize).toInt, UInt(Size.W))

  val CacheAddress = Wire(UInt(Bitsize.W))

  CacheAddress := io.MemPort.Address((Bitsize-1),0)

  //  ((24-Bitsize) + 24) = Dirty bit, ((24-Bitsize) + 23, 24) = Tag,  (23,0) = Data

  val rdwrPort = Memory(CacheAddress)

  val Tag = Wire(UInt((24-Bitsize).W))
  val DirtyBit = Wire(UInt(1.W))
  Tag := rdwrPort(((24-Bitsize) + 23),24)
  DirtyBit := rdwrPort((24-Bitsize) + 24)

  val DelayReg = RegInit(0.U(1.W))
  DelayReg := io.MemPort.Enable

  //val CompleteDelay = RegInit(0.U(1.W))

  val TempReg = RegInit(0.U(24.W))

  when(io.MemPort.Enable && DelayReg.asBool){
    when(!io.MemPort.WriteEn){ // Read
      when(Tag === io.MemPort.Address(23,Bitsize)){ // Cache Hit
        TempReg := rdwrPort(23,0)
        io.MemPort.ReadData(0) := TempReg
        io.MemPort.Completed := true.B

      }.otherwise{ // Cache Miss
        when(DirtyBit.asBool){
          StateReg := ReadWriteback
        }.otherwise{
          StateReg := CacheRead
        }
      }
    }.elsewhen(io.MemPort.WriteEn){ // Write
      when(DirtyBit.asBool){ // DIRTY
        StateReg := Writeback
      }.otherwise{ // Not Dirty 
        rdwrPort := Cat("b1".U,Cat(io.MemPort.Address(23,Bitsize).asUInt,io.MemPort.WriteData(0)).asUInt) // Write Data, tag and set bit to dirty
        io.MemPort.Completed := true.B
      }
    }
  }

  switch(StateReg){
    is(Writeback){
      io.EXT.WriteData(0) := rdwrPort(23,0)
      io.EXT.Enable := true.B
      io.EXT.WriteEn := true.B
      io.EXT.Address := io.MemPort.Address
      io.EXT.Len := 1.U

      when(io.EXT.Completed){
        StateReg := CacheWrite
      }
    }
    is(CacheWrite){
      rdwrPort := io.MemPort.ReadData(0)
      io.MemPort.Completed := true.B
      StateReg := 0.U
    }
    is(ReadWriteback){
      io.EXT.WriteData(0) := rdwrPort(23,0)
      io.EXT.Enable := true.B
      io.EXT.WriteEn := true.B
      io.EXT.Address := io.MemPort.Address
      io.EXT.Len := 1.U

      when(io.EXT.Completed){
        StateReg := CacheRead
      }
    }
    is(CacheRead){
      io.EXT.Enable := true.B
      io.EXT.Address := io.MemPort.Address
      io.EXT.Len := 1.U

      when(io.EXT.Completed){
        rdwrPort := Cat("b1".U,Cat(io.MemPort.Address(23,Bitsize).asUInt,io.EXT.ReadData(0)).asUInt) // Write Data, tag and set bit to dirty
        io.MemPort.Completed := true.B
        StateReg := 0.U
      }
    }

  }
}