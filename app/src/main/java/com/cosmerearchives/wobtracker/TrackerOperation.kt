package com.cosmerearchives.wobtracker
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
data class OperationState(val busy: Boolean=false,val state: String="Ready",val progress: TransferProgress=TransferProgress("Choose an operation"),val message: String="")
/** Shared guard and progress survive Activity recreation without repeated jobs. */
object TrackerOperation {
 private val running=AtomicBoolean(false)
 private val cancelled=AtomicBoolean(false)
 private var last=0L
 val state=MutableStateFlow(OperationState())
 fun begin(stage: String): Boolean { if(!running.compareAndSet(false,true))return false;cancelled.set(false);last=0;state.value=OperationState(true,"Preparing",TransferProgress(stage));return true }
 @Synchronized fun update(p: TransferProgress) { if(cancelled.get())error("Operation cancelled. Library data was kept safe.");val now=android.os.SystemClock.elapsedRealtime();if(p.stage!=state.value.progress.stage||now-last>=100){last=now;state.value=OperationState(true,"Processing",p)} }
 @Synchronized fun cancel(){if(state.value.progress.stage=="Committing restore")return;cancelled.set(true);state.value=state.value.copy(state="Cancelling",message="Waiting for the current safe step to stop…")}
 fun finish(status: String,text: String){running.set(false);state.value=OperationState(false,if(cancelled.get())"Cancelled" else status,TransferProgress(status),text)}
}
