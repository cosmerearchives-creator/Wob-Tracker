package com.cosmerearchives.wobtracker
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

@Composable fun BackupTools(library: WobLibrary,onRestored: ()->Unit){
 val context=LocalContext.current
 val main=remember{Handler(Looper.getMainLooper())}
 val operation by TrackerOperation.state.collectAsState()
 val busy=operation.busy
 var verified by remember{mutableStateOf<Int?>(null)}
 var checkingDatabase by remember { mutableStateOf(false) }
 var integrityResult by remember { mutableStateOf("Not checked yet.") }
 var restoreUri by remember{mutableStateOf<android.net.Uri?>(null)}
 val prefs=remember{ArcanumSharing.preferences(context)}
 var transferStatus by remember{mutableStateOf(prefs.getString("status","No transfer pending.")?:"")}
 var waiting by remember{mutableStateOf(prefs.getBoolean("pending",false))}
 var confirmCancelShare by remember { mutableStateOf(false) }
 DisposableEffect(prefs){
  val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="status")main.post{transferStatus=prefs.getString("status","")?:"";waiting=prefs.getBoolean("pending",false);if(!waiting){val outcome=prefs.getString("state","")?:"";if(!TrackerOperation.state.value.busy&&TrackerOperation.state.value.state=="Waiting"&&outcome in listOf("Completed","Failed"))TrackerOperation.finish(outcome,transferStatus);if(outcome=="Completed")onRestored()}}}
  prefs.registerOnSharedPreferenceChangeListener(listener)
  onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
 }
 fun progress(p: TransferProgress)=TrackerOperation.update(p)
 fun counts(stage: String,d: Int,t: Int)=progress(TransferProgress(stage,d.toLong(),t.toLong()))
 fun finish(state: String,text: String)=TrackerOperation.finish(state,text)
 val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
  if(uri==null)finish("Cancelled","No backup file selected.")else thread{try{
   val count=VerifiedTransferFile.save(context,uri,::progress){out->LibraryTransfer.backup(library,out,{d,t->counts("Reading backup entries",d,t)},::progress)}
   finish("Completed","Backup saved and verified for $count entries. Keep this file safe.")
  }catch(e: Exception){finish("Failed","Backup was not completed: ${e.message}")}}
 }
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArcanumSharing.MIME)){uri->
  if(uri==null)finish("Cancelled","No export file selected.")else thread{try{
   val count=VerifiedTransferFile.save(context,uri,::progress){out->LibraryTransfer.exportPackage(library,out,::progress)}
   finish("Completed","Export file saved and verified for $count WoBs. Nothing was marked as transferred.")
  }catch(e: Exception){finish("Failed","Export was not completed; entries remain queued: ${e.message}")}}
 }
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  restoreUri=null;verified=null
  if(uri==null)finish("Cancelled","No backup selected.")else thread{try{
   val count=context.contentResolver.openInputStream(uri)?.use{LibraryTransfer.verify(it,::progress)}?:error("Cannot read backup")
   main.post{verified=count;restoreUri=uri};finish("Waiting","Verified $count entries. Confirm the safe merge below; existing notes are preserved.")
  }catch(e: Exception){finish("Failed","Backup verification failed: ${e.message}")}}
 }
 fun share(){
  if(waiting || prefs.getBoolean("pending",false))return
  if(!TrackerOperation.begin("Preparing Archives share"))return
  thread{try{
   val intent=ArcanumSharing.prepare(context,library,::progress);progress(TransferProgress("Starting Android share"))
   main.post{try{
    prefs.edit().putBoolean("pending",true).putString("state","Waiting").putString("status","Waiting for Archives to verify and save this transfer. Entries remain queued.").commit()
    context.startActivity(intent);finish("Waiting","Shared with Archives. Waiting for its saved-import acknowledgment.")
   }catch(e: Exception){prefs.edit().putBoolean("pending",false).putString("status","Share failed; entries remain queued: ${e.message}").apply();finish("Failed","Could not start share: ${e.message}")}}
  }catch(e: Exception){finish("Failed","Share was not completed; entries remain queued: ${e.message}")}}
 }
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("Backup & Restore",style=MaterialTheme.typography.titleLarge)
  Text(operation.state+" · "+operation.progress.stage)
  if(operation.message.isNotBlank())Text(operation.message)
  if(busy){val p=operation.progress
   if(p.total>0){LinearProgressIndicator(progress={(p.completed.toFloat()/p.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("${100*p.completed/p.total}% · ${p.completed} / ${p.total} ${p.unit}")}
   else{LinearProgressIndicator(modifier=Modifier.fillMaxWidth());if(p.completed>0)Text("${p.completed} ${p.unit}")}
   TextButton(enabled=operation.progress.stage!="Committing restore",onClick={TrackerOperation.cancel()}){Text("Cancel operation")}
  }
  Button(enabled=!busy,onClick={if(TrackerOperation.begin("Waiting for backup location"))backup.launch("wob-tracker-backup.zip")}){Text("Create complete backup ZIP")}
  OutlinedButton(enabled=!busy,onClick={if(TrackerOperation.begin("Waiting for backup selection"))restore.launch(arrayOf("application/zip","application/octet-stream","*/*"))}){Text("Verify or restore backup")}
  if(!busy&&restoreUri!=null&&verified!=null){
   Button(onClick={val selected=restoreUri?:return@Button;if(!TrackerOperation.begin("Verifying before restore"))return@Button
    thread{try{
     val result=context.contentResolver.openInputStream(selected)?.use{LibraryTransfer.restoreMerge(library,it,{d,t->counts("Merging backup entries",d,t)},::progress)}?:error("Cannot read backup")
     main.post{restoreUri=null;verified=null;onRestored()};finish("Completed","Restore committed: ${result.first} added, ${result.second} already present. Notes and highlights preserved.")
    }catch(e: Exception){finish("Failed","Restore was not completed: ${e.message}")}}
   }){Text("Confirm safe merge restore")}
   TextButton(onClick={restoreUri=null;verified=null;finish("Cancelled","Restore cancelled. Library unchanged.")}){Text("Cancel restore")}
  }
  HorizontalDivider()
  Text("Library safety check", style=MaterialTheme.typography.titleMedium)
  Text("Read-only database integrity and highlight checks; nothing is deleted.")
  OutlinedButton(enabled=!busy && !checkingDatabase,onClick={
   checkingDatabase=true
   thread {
    val result=try { library.checkIntegrity() } catch(e:Exception) { "Could not finish database check: ${e.message}" }
    main.post { integrityResult=result;checkingDatabase=false }
   }
  }) { Text(if(checkingDatabase) "Checking database…" else "Check database health") }
  Text(integrityResult,style=MaterialTheme.typography.bodySmall)
    HorizontalDivider();Text("Transfer to Archives of Arcanum",style=MaterialTheme.typography.titleMedium)
  Text("A blue WoB Sticky and a connected personal-note Sticky, when present. Only a verified Archives save can archive the source entries.")
  OutlinedButton(enabled=!busy,onClick={if(TrackerOperation.begin("Waiting for export location"))export.launch("wob-archives-transfer.arcanum")}){Text("Create Archives export file")}
  Button(enabled=!busy&&!waiting,onClick={share()}){Text("Send to Archives of Arcanum")}
  if(waiting){
   LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
   Text("Waiting for saved-import acknowledgment")
   Text("If Archives rejected this package, cancel it before sending a newly generated one. Your WoBs remain queued.",style=MaterialTheme.typography.bodySmall)
   OutlinedButton(enabled=!busy,onClick={confirmCancelShare=true}){Text("Cancel pending share")}
  }
  if(confirmCancelShare){
   AlertDialog(
    onDismissRequest={confirmCancelShare=false},
    title={Text("Cancel pending share?")},
    text={Text("This abandons the failed package and clears its saved acknowledgment receipt. No WoBs, images, tags, notes, or export flags will be deleted. You'll be able to send a brand-new package.")},
    confirmButton={TextButton(onClick={
     confirmCancelShare=false
     try{
      ArcanumSharing.cancelPendingShare(context)
      waiting=false
      transferStatus=prefs.getString("status","Pending transfer cancelled.")?:"Pending transfer cancelled."
      TrackerOperation.finish("Cancelled",transferStatus)
     }catch(e:Exception){
      transferStatus="Could not cancel the pending transfer: ${e.message}"
     }
    }){Text("Cancel share")}},
    dismissButton={TextButton(onClick={confirmCancelShare=false}){Text("Keep waiting")}}
   )
  }
  Text(transferStatus,style=MaterialTheme.typography.bodySmall)
 }
}
