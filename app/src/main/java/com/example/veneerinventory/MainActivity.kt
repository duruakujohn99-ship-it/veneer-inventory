package com.example.veneerinventory

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class Row(val date:String,val supplier:String,val code:String,val barcode:String,val len:Double,val wide:Double,val sheets:Int,val area:Double)
class MainActivity:AppCompatActivity(){
 private lateinit var preview:PreviewView; private lateinit var supplier:EditText; private lateinit var code:EditText; private lateinit var barcode:EditText; private lateinit var len:EditText; private lateinit var wide:EditText; private lateinit var sheets:EditText; private lateinit var areaText:TextView; private lateinit var status:TextView; private lateinit var summary:TextView
 private val rows= mutableListOf<Row>(); private val executor=Executors.newSingleThreadExecutor(); private val prefs by lazy{getSharedPreferences("inventory",MODE_PRIVATE)}; private var last=""; private var lastAt=0L
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it) camera() else status.text="Camera permission required."}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); ui(); load(); updateSummary(); if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera() else permission.launch(Manifest.permission.CAMERA)}
 private fun ui(){val root=LinearLayout(this).apply{orientation=1;setPadding(16,10,16,16)}; root.addView(TextView(this).apply{text="VENEER INVENTORY";textSize=23f}); preview=PreviewView(this).apply{layoutParams=LinearLayout.LayoutParams(-1,dp(180))};root.addView(preview);status=TextView(this).apply{text="Point camera at barcode or QR code."};root.addView(status)
 supplier=field("Supplier (optional)");code=field("Stock / product code (optional)");barcode=field("Scanned barcode");len=field("Length in cm");wide=field("Width in cm");sheets=field("Number of sheets");listOf(supplier,code,barcode,len,wide,sheets).forEach{root.addView(it)}
 areaText=TextView(this).apply{textSize=18f};root.addView(areaText);root.addView(Button(this).apply{text="Calculate area";setOnClickListener{calc()}});root.addView(Button(this).apply{text="Save inventory record";setOnClickListener{save()}});root.addView(Button(this).apply{text="Export CSV for Excel";setOnClickListener{export()}});summary=TextView(this);root.addView(summary);setContentView(ScrollView(this).apply{addView(root)}) }
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt(); private fun field(h:String)=EditText(this).apply{hint=h;singleLine=true}
 private fun camera(){val future=ProcessCameraProvider.getInstance(this);future.addListener({try{val provider=future.get();val p=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};val a=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();val scanner=BarcodeScanning.getClient();a.setAnalyzer(executor){proxy->val im=proxy.image;if(im==null){proxy.close();return@setAnalyzer};scanner.process(InputImage.fromMediaImage(im,proxy.imageInfo.rotationDegrees)).addOnSuccessListener{found->val raw=found.firstOrNull{!it.rawValue.isNullOrBlank()}?.rawValue;if(raw!=null&&raw!=last&&System.currentTimeMillis()-lastAt>1200){last=raw;lastAt=System.currentTimeMillis();runOnUiThread{barcode.setText(raw);status.text="Barcode captured. Verify dimensions from label.";parseSample(raw)}}}.addOnCompleteListener{proxy.close()}};provider.unbindAll();provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,a);status.text="Camera ready. Aim at barcode."}catch(e:Exception){status.text="Camera error: ${e.message}"}},ContextCompat.getMainExecutor(this))}
 private fun parseSample(raw:String){when(raw.filterNot{it.isWhitespace()}){"00000430900460240055"->{len.setText("90");wide.setText("46");sheets.setText("24")};"4201153000424040025"->{len.setText("40");wide.setText("25");sheets.setText("24")};else->{}};calc()}
 private fun calc():Double?{val l=len.text.toString().replace(",",".").toDoubleOrNull();val w=wide.text.toString().replace(",",".").toDoubleOrNull();val s=sheets.text.toString().toIntOrNull();if(l==null||w==null||s==null||l<=0||w<=0||s<=0){areaText.text="Area: enter valid length, width and sheets";return null};val a=l*w*s/10000.0;areaText.text="Total area: %.2f m²".format(Locale.US,a);return a}
 private fun save(){val l=len.text.toString().replace(",",".").toDoubleOrNull();val w=wide.text.toString().replace(",",".").toDoubleOrNull();val s=sheets.text.toString().toIntOrNull();val a=calc();if(l==null||w==null||s==null||a==null){Toast.makeText(this,"Enter valid dimensions first",Toast.LENGTH_LONG).show();return};rows.add(Row(SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date()),supplier.text.toString(),code.text.toString(),barcode.text.toString(),l,w,s,a));persist();updateSummary();Toast.makeText(this,"Record saved",Toast.LENGTH_SHORT).show();barcode.setText("");last=""}
 private fun persist(){prefs.edit().putString("data",rows.joinToString("\n"){listOf(it.date,it.supplier,it.code,it.barcode,it.len,it.wide,it.sheets,it.area).joinToString("\t")}).apply()}
 private fun load(){(prefs.getString("data","")?:"").lineSequence().filter{it.isNotBlank()}.forEach{p->val a=p.split("\t");if(a.size==8)runCatching{rows.add(Row(a[0],a[1],a[2],a[3],a[4].toDouble(),a[5].toDouble(),a[6].toInt(),a[7].toDouble()))}}
 private fun updateSummary(){summary.text="Saved records: ${rows.size} | Sheets: ${rows.sumOf{it.sheets}} | Total: %.2f m²".format(Locale.US,rows.sumOf{it.area})}
 private fun quote(s:String)="\""+s.replace("\"","\"\"")+"\""
 private fun export(){val csv=buildString{appendLine("Date,Supplier,StockCode,Barcode,Length_cm,Width_cm,Sheets,Total_m2");rows.forEach{r->appendLine(listOf(r.date,r.supplier,r.code,r.barcode,r.len,r.wide,r.sheets,String.format(Locale.US,"%.4f",r.area)).joinToString(","){quote(it.toString())})}};try{val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,"veneer_inventory_${System.currentTimeMillis()}.csv");put(MediaStore.Downloads.MIME_TYPE,"text/csv");put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)};val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Could not create file");contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(csv)};Toast.makeText(this,"CSV saved in Downloads",Toast.LENGTH_LONG).show()}catch(e:Exception){Toast.makeText(this,"Export failed: ${e.message}",Toast.LENGTH_LONG).show()}}
 override fun onDestroy(){super.onDestroy();executor.shutdown()}
}
