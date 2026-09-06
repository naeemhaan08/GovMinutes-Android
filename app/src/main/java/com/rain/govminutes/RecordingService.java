package com.rain.govminutes;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import com.rain.govminutes.audio.WavIO;

public class RecordingService extends Service {
    public static final String ACTION_START="com.rain.govminutes.START";
    public static final String ACTION_STOP="com.rain.govminutes.STOP";
    public static final String EVENT_STOPPED="com.rain.govminutes.RECORDING_STOPPED";
    public static final String EXTRA_PATH="path";
    private static final int SR=48000, CH=AudioFormat.CHANNEL_IN_MONO, ENC=AudioFormat.ENCODING_PCM_16BIT;
    private volatile boolean running=false;
    private AudioRecord recorder;
    private Thread worker;
    private File sessionDir, original;

    @Override public void onCreate(){super.onCreate();createChannel();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_START.equals(intent.getAction()))startRecording();
        else if(ACTION_STOP.equals(intent.getAction()))stopRecording();
        return START_STICKY;
    }

    private void startRecording(){
        if(running)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return;}
        File base=new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),"GovMinutes"); base.mkdirs();
        String stamp=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());
        sessionDir=new File(base,"Meeting_"+stamp); sessionDir.mkdirs();
        original=new File(sessionDir,"Original.wav");
        int min=AudioRecord.getMinBufferSize(SR,CH,ENC); int buf=Math.max(min,SR*2);
        int source=chooseSource();
        recorder=new AudioRecord(source,SR,CH,ENC,buf);
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){ recorder.release(); recorder=null; stopSelf(); return; }
        running=true;
        startForeground(1001,notification("Recording meeting…"));
        worker=new Thread(()->recordLoop(buf),"GovMinutesRecorder"); worker.start();
    }

    private int chooseSource(){
        if(Build.VERSION.SDK_INT>=24){
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            String p=am.getProperty("android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED");
            if("true".equalsIgnoreCase(p)) return MediaRecorder.AudioSource.UNPROCESSED;
        }
        return MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    private void recordLoop(int bufferSize){
        long pcmBytes=0;
        try(RandomAccessFile out=new RandomAccessFile(original,"rw")){
            WavIO.writeHeader(out,SR,1,0);
            byte[] buf=new byte[bufferSize]; recorder.startRecording();
            while(running){ int n=recorder.read(buf,0,buf.length); if(n>0){out.write(buf,0,n);pcmBytes+=n;} }
            try{recorder.stop();}catch(Exception ignored){}
            WavIO.writeHeader(out,SR,1,pcmBytes);
        }catch(Exception ignored){} finally {
            if(recorder!=null){recorder.release();recorder=null;}
            getSharedPreferences("gm",MODE_PRIVATE).edit().putString("last_session",sessionDir==null?"":sessionDir.getAbsolutePath()).apply();
            Intent done=new Intent(EVENT_STOPPED).setPackage(getPackageName());
            if(sessionDir!=null)done.putExtra(EXTRA_PATH,sessionDir.getAbsolutePath()); sendBroadcast(done);
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }

    private void stopRecording(){ running=false; }
    private Notification notification(String text){
        Intent stop=new Intent(this,RecordingService.class).setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"govminutes_record")
                .setSmallIcon(com.rain.govminutes.R.drawable.ic_stat_govminutes)
                .setContentTitle("GovMinutes").setContentText(text).setOngoing(true)
                .addAction(new Notification.Action.Builder(com.rain.govminutes.R.drawable.ic_stat_govminutes,"Stop",pi).build()).build();
    }
    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("govminutes_record","Meeting recording",NotificationManager.IMPORTANCE_LOW));} }
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
