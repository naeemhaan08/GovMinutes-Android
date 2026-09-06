package com.rain.govminutes;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.*;
import java.io.*;
import java.util.*;
import com.rain.govminutes.audio.*;

public class MainActivity extends Activity {
    private static final int REQ=10;
    private TextView status, device, transcript, folder;
    private Button recordBtn, processBtn, transcribeBtn, playOriginalBtn, playProcessedBtn, saveAllBtn;
    private boolean recording=false;
    private File sessionDir, original, processed, transcriptFile;
    private List<Diarizer.Segment> segments=new ArrayList<>();
    private MediaPlayer player;

    private final BroadcastReceiver stoppedReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String p=i.getStringExtra(RecordingService.EXTRA_PATH);
            if(p!=null){loadSession(new File(p));status("Recording saved. Process audio next.");}
            recording=false;recordBtn.setText("Start Meeting");updateButtons();
        }
    };

    @Override public void onCreate(Bundle b){super.onCreate(b);buildUi();registerStopped();restoreLast();updateDevice();updateButtons();}
    @Override protected void onDestroy(){super.onDestroy();try{unregisterReceiver(stoppedReceiver);}catch(Exception ignored){}if(player!=null)player.release();}

    private void buildUi(){
        int pad=dp(18);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);root.setBackgroundColor(Color.rgb(245,247,246));
        ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);
        TextView title=t("GovMinutes",30,true);root.addView(title);
        TextView sub=t("Local Meeting Capture • Samsung / Pixel",14,false);sub.setTextColor(Color.DKGRAY);root.addView(sub,lp(-1,dp(36)));
        device=t("",13,false);root.addView(device);
        status=t("Ready",15,true);status.setPadding(0,dp(14),0,dp(10));root.addView(status);

        recordBtn=button("Start Meeting");recordBtn.setOnClickListener(v->toggleRecord());root.addView(recordBtn);
        processBtn=button("Process Audio");processBtn.setOnClickListener(v->process());root.addView(processBtn);
        transcribeBtn=button("Generate Bangla Transcript");transcribeBtn.setOnClickListener(v->transcribe());root.addView(transcribeBtn);

        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        playOriginalBtn=button("Play Original");playProcessedBtn=button("Play Processed");
        row.addView(playOriginalBtn,new LinearLayout.LayoutParams(0,dp(52),1));row.addView(playProcessedBtn,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(row);
        playOriginalBtn.setOnClickListener(v->play(original));playProcessedBtn.setOnClickListener(v->play(processed));

        saveAllBtn=button("Save All to Downloads");saveAllBtn.setOnClickListener(v->saveAllToDownloads());root.addView(saveAllBtn);

        folder=t("No meeting yet",12,false);folder.setTextColor(Color.GRAY);folder.setPadding(0,dp(10),0,dp(10));root.addView(folder);
        TextView h=t("বাংলা Transcript",20,true);h.setPadding(0,dp(12),0,dp(8));root.addView(h);
        transcript=t("Speaker-wise transcript will appear here.",16,false);transcript.setTextIsSelectable(true);transcript.setLineSpacing(0,1.18f);transcript.setPadding(dp(14),dp(14),dp(14),dp(20));transcript.setBackgroundColor(Color.WHITE);root.addView(transcript,new LinearLayout.LayoutParams(-1,-2));
    }

    private void toggleRecord(){
        if(!hasPermission()){requestNeeded();return;}
        if(!recording){
            Intent i=new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_START);startForegroundService(i);recording=true;recordBtn.setText("Stop Meeting");status("Recording original audio…");
        }else{
            startService(new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_STOP));recordBtn.setEnabled(false);status("Saving recording…");
        }
        updateButtons();
    }

    private void process(){
        if(original==null||!original.exists())return;
        processBtn.setEnabled(false);transcribeBtn.setEnabled(false);saveAllBtn.setEnabled(false);status("Processing locally…");
        new Thread(()->{
            try{
                processed=new File(sessionDir,"Processed.wav");
                AudioProcessor.process(original,processed,48000,(p,s)->runOnUiThread(()->status(s+"  "+p+"%")));
                runOnUiThread(()->status("Detecting speakers conservatively…"));
                segments=Diarizer.diarize(original,48000);writeDiarization();
                runOnUiThread(()->{status("Processed audio ready • distant voices lifted • "+countSpeakers()+" speaker cluster(s)");updateButtons();});
            }catch(Exception e){runOnUiThread(()->{status("Processing failed: "+e.getMessage());updateButtons();});}
        },"GM-Process").start();
    }

    private void transcribe(){
        if(processed==null||!processed.exists()){status("Process the audio first.");return;}
        if(segments==null||segments.isEmpty()){
            try{segments=Diarizer.diarize(original,48000);}catch(Exception e){status("Speaker detection failed.");return;}
        }
        transcribeBtn.setEnabled(false);saveAllBtn.setEnabled(false);transcript.setText("Loading the embedded Bangla model…");status("Starting fully offline Bangla transcription…");
        new OnDeviceTranscriber(this).transcribe(processed,48000,segments,new OnDeviceTranscriber.Callback(){
            @Override public void onProgress(int done,int total,String msg){runOnUiThread(()->status(msg+"  "+done+"/"+total));}
            @Override public void onComplete(String text){runOnUiThread(()->{
                transcript.setText(text);
                try{transcriptFile=new File(sessionDir,"Bangla_Transcript.txt");try(FileOutputStream f=new FileOutputStream(transcriptFile)){f.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}
                status("Done • Original + Processed + Bangla Transcript ready");updateButtons();
            });}
            @Override public void onError(String message){runOnUiThread(()->{status("Transcript failed • "+message);transcript.setText(message);transcribeBtn.setEnabled(true);updateButtons();});}
        });
    }

    private void saveAllToDownloads(){
        if(original==null||!original.exists()||processed==null||!processed.exists()||transcriptFile==null||!transcriptFile.exists()){
            status("Generate the Bangla transcript first. All three files are saved together.");return;
        }
        if(Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ);status("Allow storage access, then press Save All again.");return;
        }
        saveAllBtn.setEnabled(false);status("Saving Original + Processed + Transcript to Downloads…");
        new Thread(()->{
            try{
                String meeting=sessionDir==null?"Meeting":sessionDir.getName();
                if(Build.VERSION.SDK_INT>=29){
                    String rel=Environment.DIRECTORY_DOWNLOADS+"/GovMinutes/"+meeting;
                    saveMediaStore(original,"Original.wav","audio/wav",rel);
                    saveMediaStore(processed,"Processed.wav","audio/wav",rel);
                    saveMediaStore(transcriptFile,"Bangla_Transcript.txt","text/plain",rel);
                }else{
                    File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"GovMinutes/"+meeting);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create Downloads folder");
                    copyFile(original,new File(dir,"Original.wav"));copyFile(processed,new File(dir,"Processed.wav"));copyFile(transcriptFile,new File(dir,"Bangla_Transcript.txt"));
                }
                runOnUiThread(()->{status("Saved ✓ Downloads/GovMinutes/"+meeting+" • 3 files");updateButtons();});
            }catch(Exception e){runOnUiThread(()->{status("Save failed: "+e.getMessage());updateButtons();});}
        },"GM-SaveDownloads").start();
    }

    private void saveMediaStore(File src,String name,String mime,String relativePath)throws IOException{
        ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,mime);v.put(MediaStore.MediaColumns.RELATIVE_PATH,relativePath);v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("Downloads storage unavailable");
        try(InputStream in=new FileInputStream(src);OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("Could not open Downloads file");byte[] b=new byte[128*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,done,null,null);
    }

    private static void copyFile(File src,File dst)throws IOException{try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dst)){byte[]b=new byte[128*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}}

    private int countSpeakers(){Set<Integer>s=new HashSet<>();for(Diarizer.Segment x:segments)if(x.speaker>0)s.add(x.speaker);return s.size();}
    private void writeDiarization(){try(FileWriter w=new FileWriter(new File(sessionDir,"Speaker_Timeline.csv"))){w.write("start_ms,end_ms,speaker,confidence\n");for(Diarizer.Segment s:segments)w.write(s.startMs+","+s.endMs+",Speaker "+s.speaker+","+String.format(Locale.US,"%.2f",s.confidence)+"\n");}catch(Exception ignored){}}

    private void play(File f){if(f==null||!f.exists())return;try{if(player!=null){player.release();player=null;}player=new MediaPlayer();player.setDataSource(f.getAbsolutePath());player.prepare();player.start();status("Playing "+f.getName());}catch(Exception e){status("Could not play audio.");}}

    private void loadSession(File dir){sessionDir=dir;original=new File(dir,"Original.wav");processed=new File(dir,"Processed.wav");transcriptFile=new File(dir,"Bangla_Transcript.txt");folder.setText(dir.getAbsolutePath());if(transcriptFile.exists())try{transcript.setText(new String(java.nio.file.Files.readAllBytes(transcriptFile.toPath()),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
    private void restoreLast(){String p=getSharedPreferences("gm",MODE_PRIVATE).getString("last_session","");if(!p.isEmpty())loadSession(new File(p));}

    private void updateDevice(){String brand=Build.MANUFACTURER==null?"":Build.MANUFACTURER;String model=Build.MODEL;boolean preferred=brand.equalsIgnoreCase("samsung")||brand.equalsIgnoreCase("google");device.setText((preferred?"✓ Preferred device: ":"Device: ")+brand+" "+model+"\nEmbedded Bangla ASR • Fully offline • no speech API");}

    private void updateButtons(){boolean has=original!=null&&original.exists();boolean hasProcessed=processed!=null&&processed.exists();boolean hasTranscript=transcriptFile!=null&&transcriptFile.exists();processBtn.setEnabled(has&&!recording);playOriginalBtn.setEnabled(has);playProcessedBtn.setEnabled(hasProcessed);transcribeBtn.setEnabled(hasProcessed&&!recording);saveAllBtn.setEnabled(has&&hasProcessed&&hasTranscript&&!recording);if(!recording)recordBtn.setEnabled(true);}
    private boolean hasPermission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void requestNeeded(){List<String>p=new ArrayList<>();if(!hasPermission())p.add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);requestPermissions(p.toArray(new String[0]),REQ);}
    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){super.onRequestPermissionsResult(r,p,g);if(r==REQ&&hasPermission())status("Permission granted. Press Start Meeting.");}
    private void registerStopped(){IntentFilter f=new IntentFilter(RecordingService.EVENT_STOPPED);if(Build.VERSION.SDK_INT>=33)registerReceiver(stoppedReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(stoppedReceiver,f);}
    private void status(String s){status.setText(s);}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(50));return b;}
    private TextView t(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(23,32,30));if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return v;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
}
