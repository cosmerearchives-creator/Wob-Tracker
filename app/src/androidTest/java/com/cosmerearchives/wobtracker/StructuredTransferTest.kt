package com.cosmerearchives.wobtracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class) class StructuredTransferTest {
 @Test fun optionalNotesColorsStableLinksAndVerifiedBackupProgress(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val library=WobLibrary(context)
  val ids=setOf(987610,987611,987612)
  for(id in ids){library.saveSource(SavedWob(id,"Synthetic event","2026-11-05","Complete transcript $id","Cosmere","","",originalUrl="https://wob.coppermind.net/entry/$id/"));library.setArchived(id,false);library.saveFlags(id,true,true);library.savePersonal(id,"Personal",if(id==987610)"" else "Personal note $id")}
  val rows=LibraryTransfer.recordsForIds(library,ids)
  val progress=mutableListOf<TransferProgress>()
  val manifest=ArcanumSharing.manifest(rows,"test",{progress.add(it)})
  val items=manifest.getJSONArray("items");val links=manifest.getJSONArray("relationships")
  assertEquals(5,items.length());assertEquals(2,links.length())
  val byId=(0 until items.length()).associate{val item=items.getJSONObject(it);item.getString("itemId") to item}
  assertFalse(byId.containsKey("wob:987610:personal-note"))
  assertEquals("#cfe8ff",byId.getValue("wob:987611").getJSONObject("appearance").getString("background"))
  assertEquals("#ffffff",byId.getValue("wob:987611:personal-note").getJSONObject("appearance").getString("background"))
  assertFalse(byId.getValue("wob:987611").getString("body").contains("Personal note 987611"))
  for(i in 0 until links.length()){val e=links.getJSONObject(i);assertTrue(byId.containsKey(e.getString("from")));assertTrue(byId.containsKey(e.getString("to")));assertTrue(e.getBoolean("moveTogether"))}
  val repeated=ArcanumSharing.manifest(rows,"different-token")
  assertEquals(links.toString(),repeated.getJSONArray("relationships").toString())
  assertTrue(progress.any{it.total==3L})
  val dir=File(context.cacheDir,"arcanum").apply{mkdirs()};val file=File(dir,"verified-test.zip")
  val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
  val events=mutableListOf<TransferProgress>()
  VerifiedTransferFile.save(context,uri,{events.add(it)}){out->LibraryTransfer.backup(library,out,stage={events.add(it)})}
  assertTrue(events.any{it.stage=="Verifying saved file"&&it.completed==it.total&&it.total>0})
  assertTrue(file.inputStream().use{LibraryTransfer.verify(it,{events.add(it)})}>=3)
  val bytes=file.readBytes();val before=LibraryTransfer.recordsForIds(library,ids).toString()
  library.writableDatabase.delete("entries","id=?",arrayOf("987612"))
  var failed=false
  try{LibraryTransfer.restoreMerge(library,ByteArrayInputStream(bytes),{d,_->if(d>0)error("Synthetic interrupted restore")})}catch(e: Exception){failed=true}
  assertTrue(failed);assertEquals(2,LibraryTransfer.recordsForIds(library,ids).length())
  LibraryTransfer.restoreMerge(library,ByteArrayInputStream(bytes))
  assertEquals(before,LibraryTransfer.recordsForIds(library,ids).toString())
  assertTrue(TrackerOperation.begin("Synthetic test"));assertFalse(TrackerOperation.begin("Duplicate"));TrackerOperation.cancel()
  try{TrackerOperation.update(TransferProgress("Processing"));fail("Cancelled operation must stop")}catch(e: Exception){}
  TrackerOperation.finish("Failed","Cancelled synthetic operation");assertEquals("Cancelled",TrackerOperation.state.value.state)
  library.close()
 }
 @Test fun imageAssetHasOneDescriptorAndOneZipEntry() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val library=WobLibrary(context)
  val id=989901
  library.saveSource(SavedWob(id,"Image transfer test","2026-11-10","Test transcript","cosmere","",""))
  library.saveImage(id,"image/jpeg",byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte(),0xD9.toByte()))
  val rows=LibraryTransfer.recordsForIds(library,setOf(id))
  val image=library.imageFor(id)!!
  val manifest=ArcanumSharing.manifest(rows,"image-test",images=mapOf(id to image))
  val items=manifest.getJSONArray("items")
  val paths=mutableListOf<String>()
  for(i in 0 until items.length()){
   val assets=items.getJSONObject(i).getJSONArray("assets")
   for(j in 0 until assets.length())paths.add(assets.getJSONObject(j).getString("path"))
  }
  assertEquals(listOf("assets/wob-$id.jpg"),paths)
  val output=ByteArrayOutputStream()
  ArcanumSharing.write(rows,"image-test",output,library=library)
  java.util.zip.ZipInputStream(ByteArrayInputStream(output.toByteArray())).use{zip->
   val names=mutableListOf<String>()
   var entry=zip.nextEntry
   while(entry!=null){names.add(entry.name);zip.closeEntry();entry=zip.nextEntry}
   assertEquals(1,names.count{it=="assets/wob-$id.jpg"})
   assertTrue(names.contains("manifest.json"))
  }
  library.deleteImage(id)
  library.close()
 }

}
