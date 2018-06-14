//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Issue Slot Logic
//--------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Note: stores (and AMOs) are "broken down" into 2 uops, but stored within a single issue-slot.
// TODO XXX make a separate issueSlot for MemoryIssueSlots, and only they break apart stores.

package boom.exu

import chisel3._
import chisel3.util.Valid
import FUConstants._
import freechips.rocketchip.config.Parameters
import boom.common._
import boom.util._

class IssueSlotIO(num_wakeup_ports: Int)(implicit p: Parameters) extends BoomBundle()(p)
{
   val valid          = Output(Bool())
   val will_be_valid  = Output(Bool()) // TODO code review, do we need this signal so explicitely?
   val request        = Output(Bool())
   val request_hp     = Output(Bool())
   val grant          = Input(Bool())

   val brinfo         = Input(new BrResolutionInfo())
   val kill           = Input(Bool()) // pipeline flush
   val clear          = Input(Bool()) // entry being moved elsewhere (not mutually exclusive with grant)

   val wakeup_dsts    = Flipped(Vec(num_wakeup_ports, Valid(new WakeupPdst())))
   val in_uop         = Flipped(Valid(new MicroOp())) // if valid, this WILL overwrite an entry!
   val updated_uop    = Output(new MicroOp()) // the updated slot uop; will be shifted upwards in a collasping queue.
   val uop            = Output(new MicroOp()) // the current Slot's uop. Sent down the pipeline when issued.
      
   val vl             = Input(UInt(width=VL_SZ.W)) // The global vector length
   val lsu_ldq_head_eidx   = Input(UInt())
   val lsu_ldq_head        = Input(UInt())
   val lsu_stq_head_eidx   = Input(UInt())
   val lsu_stq_head        = Input(UInt())
   val commit_load_at_rob_head = Input(Bool())
   val commit_store_at_rob_head = Input(Bool())
   // TODO_vec: All this logic probably needs to be removed when separate vector memory unit is implemented
   // For now this tracks element indices of ops in the lsu ldq, we don't issue ops until the LDQ is ready to receive them
   // This is actually very bad since it is essentially unpipelined vector loads
   val debug = {
     val result = new Bundle {
       val p1 = Bool()
       val p2 = Bool()
       val p3 = Bool()
    }
    Output(result)
  }

   override def cloneType = new IssueSlotIO(num_wakeup_ports)(p).asInstanceOf[this.type]
}


class IssueSlot(num_slow_wakeup_ports: Int, containsVec: Boolean)(implicit p: Parameters)
   extends BoomModule()(p)
   with IssueUnitConstants
{
   val io = IO(new IssueSlotIO(num_slow_wakeup_ports))

   // slot invalid?
   // slot is valid, holding 1 uop
   // slot is valid, holds 2 uops (like a store)
   def isInvalid = slot_state === s_invalid
   def isValid = slot_state =/= s_invalid

   val updated_state = Wire(UInt()) // the next state of this slot (which might then get moved to a new slot)
   val updated_eidx  = Wire(UInt())
   val updated_uopc  = Wire(UInt()) // the next uopc of this slot (which might then get moved to a new slot)
   val updated_lrs1_rtype = Wire(UInt()) // the next reg type of this slot (which might then get moved to a new slot)
   val updated_lrs2_rtype = Wire(UInt()) // the next reg type of this slot (which might then get moved to a new slot)
   val next_p1  = Wire(Bool())
   val next_p2  = Wire(Bool())
   val next_p3  = Wire(Bool())

   val slot_state    = RegInit(s_invalid)
   val slot_p1       = RegNext(next_p1, init = false.B)
   val slot_p2       = RegNext(next_p2, init = false.B)
   val slot_p3       = RegNext(next_p3, init = false.B)
   val slot_is_2uops = Reg(Bool())

   // these signals are the "next_p*" for the current slot's micro-op.
   // they are important for shifting the current slotUop up to an other entry.
   // TODO need a better name for these signals
   val out_p1 = Wire(Bool()); out_p1 := slot_p1
   val out_p2 = Wire(Bool()); out_p2 := slot_p2
   val out_p3 = Wire(Bool()); out_p3 := slot_p3

   val slotUop = Reg(init = NullMicroOp)
   val incr_eidx = slotUop.eidx + slotUop.rate
   val next_eidx = Mux(incr_eidx > io.vl, io.vl, incr_eidx) // The next eidx after this gets executed

   //-----------------------------------------------------------------------------
   // next slot state computation
   // compute the next state for THIS entry slot (in a collasping queue, the
   // current uop may get moved elsewhere, and a new slot can get moved into
   // here

   when (io.kill)
   {
      slot_state := s_invalid
   }
   .elsewhen (io.in_uop.valid)
   {
      slot_state := io.in_uop.bits.iw_state
   }
   .elsewhen (io.clear)
   {
      slot_state := s_invalid
   }
   .otherwise
   {
      slot_state := updated_state
   }

   //-----------------------------------------------------------------------------
   // "update" state
   // compute the next state for the micro-op in this slot. This micro-op may
   // be moved elsewhere, so the "updated_state" travels with it.
   
   // defaults
   updated_state := slot_state
   updated_uopc := slotUop.uopc
   updated_eidx := slotUop.eidx
   updated_lrs1_rtype := slotUop.lrs1_rtype
   updated_lrs2_rtype := slotUop.lrs2_rtype

   val next_next_eidx = next_eidx + slotUop.rate
   when (io.kill ||
         (io.grant && (slot_state === s_valid_1)) ||
         (io.grant && (slot_state === s_valid_2) && slot_p1 && slot_p2))
   {
      updated_state := s_invalid
      if (containsVec) {
         updated_eidx := next_eidx
         when (slotUop.vec_val && updated_eidx < io.vl) {
            updated_state := slot_state
            when (slotUop.lrs1_rtype === RT_VEC) {
               out_p1 := next_next_eidx <= slotUop.prs1_eidx
            }
            when (slotUop.lrs2_rtype === RT_VEC) {
               out_p2 := next_next_eidx <= slotUop.prs2_eidx
            }
            when (slotUop.lrs3_rtype === RT_VEC) {
               out_p3 := next_next_eidx <= slotUop.prs3_eidx
            }
         }
      }
   }
   .elsewhen (io.grant && (slot_state === s_valid_2))
   {
      updated_state := s_valid_1
      when (slot_p1)
      {
         slotUop.uopc := uopSTD
         updated_uopc := uopSTD
         slotUop.lrs1_rtype := RT_X
         updated_lrs1_rtype := RT_X

      }
      .otherwise
      {
         slotUop.lrs2_rtype := RT_X
         updated_lrs2_rtype := RT_X
      }
   }

   when (io.in_uop.valid)
   {
      slotUop := io.in_uop.bits
      assert (isInvalid || io.clear || io.kill, "trying to overwrite a valid issue slot.")
   } .otherwise {
      slotUop.eidx := updated_eidx
   }
      

   // Wakeup Compare Logic
   next_p1 := false.B
   next_p2 := false.B
   next_p3 := false.B

   when (io.in_uop.valid)
   {
      next_p1 := !(io.in_uop.bits.prs1_busy)
      next_p2 := !(io.in_uop.bits.prs2_busy)
      next_p3 := !(io.in_uop.bits.prs3_busy)
   }
   .otherwise
   {
      next_p1 := out_p1
      next_p2 := out_p2
      next_p3 := out_p3
   }

   // TODO_Vec: Support vector chaining
   for (i <- 0 until num_slow_wakeup_ports)
   {
      when (io.wakeup_dsts(i).valid && (io.wakeup_dsts(i).bits.pdst === slotUop.pop1))
      {
         out_p1 := true.B
         if (containsVec) {
            when (slotUop.vec_val && slotUop.lrs1_rtype === RT_VEC) {
               out_p1 := next_eidx <= io.wakeup_dsts(i).bits.eidx
               slotUop.prs1_eidx := io.wakeup_dsts(i).bits.eidx
            }
         }
      }
      when (io.wakeup_dsts(i).valid && (io.wakeup_dsts(i).bits.pdst === slotUop.pop2))
      {
         out_p2 := true.B
         if (containsVec) {
            when (slotUop.vec_val && slotUop.lrs2_rtype === RT_VEC) {
               out_p2 := next_eidx <= io.wakeup_dsts(i).bits.eidx
               slotUop.prs2_eidx := io.wakeup_dsts(i).bits.eidx
            }
         }
      }
      when (io.wakeup_dsts(i).valid && (io.wakeup_dsts(i).bits.pdst === slotUop.pop3))
      {
         out_p3 := true.B
         if (containsVec) {
            when (slotUop.vec_val && slotUop.lrs3_rtype === RT_VEC) {
               out_p3 := next_eidx <= io.wakeup_dsts(i).bits.eidx
               slotUop.prs3_eidx := io.wakeup_dsts(i).bits.eidx
            }
         }
      }
   }

   // Handle branch misspeculations
   val out_br_mask = GetNewBrMask(io.brinfo, slotUop)

   // was this micro-op killed by a branch? if yes, we can't let it be valid if
   // we compact it into an other entry
   when (IsKilledByBranch(io.brinfo, slotUop))
   {
      updated_state := s_invalid
   }

   when (!io.in_uop.valid)
   {
      slotUop.br_mask := out_br_mask
   }


   //-------------------------------------------------------------
   // Request Logic
   io.request := isValid && slot_p1 && slot_p2 && slot_p3 && !io.kill
   val high_priority = slotUop.is_br_or_jmp
//   io.request_hp := io.request && high_priority
   io.request_hp := false.B



   when (slot_state === s_valid_1)
   {
      io.request := slot_p1 && slot_p2 && slot_p3 && !io.kill
      if (containsVec){
         when (slotUop.uopc === uopVLD) {
            io.request := (slot_p1 && slot_p2 && slot_p3 && !io.kill
               && slotUop.eidx === io.lsu_ldq_head_eidx
               && slotUop.ldq_idx === io.lsu_ldq_head
               && io.commit_load_at_rob_head)
         } .elsewhen (slotUop.uopc === uopVST) {
            io.request := (slot_p1 && slot_p2 && slot_p3 && !io.kill
               && slotUop.eidx === io.lsu_stq_head_eidx
               && slotUop.stq_idx === io.lsu_stq_head
               && io.commit_store_at_rob_head)
         }
      }

   }
   .elsewhen (slot_state === s_valid_2)
   {
      io.request := (slot_p1 || slot_p2)  && !io.kill
   }
   .otherwise
   {
      io.request := false.B
   }


   //assign outputs
   io.valid         := isValid
   io.uop           := slotUop
   if (containsVec) {
      io.will_be_valid := isValid &&
                          !(io.grant && (updated_eidx >= io.vl || !slotUop.vec_val)
                          && ((slot_state === s_valid_1) || (slot_state === s_valid_2) && slot_p1 && slot_p2))
   } else {
      io.will_be_valid := isValid &&
                          !(io.grant
                          && ((slot_state === s_valid_1) || (slot_state === s_valid_2) && slot_p1 && slot_p2))
   }


   io.updated_uop           := slotUop
   io.updated_uop.iw_state  := updated_state
   io.updated_uop.uopc      := updated_uopc
   io.updated_uop.lrs1_rtype:= updated_lrs1_rtype
   io.updated_uop.lrs2_rtype:= updated_lrs2_rtype
   io.updated_uop.br_mask   := out_br_mask
   io.updated_uop.prs1_busy := !out_p1
   io.updated_uop.prs2_busy := !out_p2
   io.updated_uop.prs3_busy := !out_p3
   io.updated_uop.eidx      := updated_eidx

   when (slot_state === s_valid_2)
   {
      when (slot_p1 && slot_p2)
      {
         ; // send out the entire instruction as one uop
      }
      .elsewhen (slot_p1)
      {
         io.uop.uopc := slotUop.uopc
         io.uop.lrs2_rtype := RT_X
      }
      .elsewhen (slot_p2)
      {
         io.uop.uopc := uopSTD
         io.uop.lrs1_rtype := RT_X
      }
   }

   // debug outputs
   io.debug.p1 := slot_p1
   io.debug.p2 := slot_p2
   io.debug.p3 := slot_p3

   override val compileOptions = chisel3.core.ExplicitCompileOptions.NotStrict.copy(explicitInvalidate = true)
}

