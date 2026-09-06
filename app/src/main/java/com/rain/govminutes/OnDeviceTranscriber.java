package com.rain.govminutes;

import android.content.*;
import android.media.AudioFormat;
import android.os.*;
import android.speech.*;
import java.io.*;
import java.util.*;
import com.rain.govminutes.audio.Diarizer;

public final class OnDeviceTranscriber {
    public interface Callback { void onProgress(int done,int total,String message); void onComplete(String transcript); void onError(String message); }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean finishedOne=false;

    public OnDeviceTranscriber(Context c){context=c.getApplicationContext();}

    public void transcribe(File processedWav,int sampleRate,List<Diarizer.Segment> segments,Callback cb){
        if(Build.VERSION.SDK_INT<33){cb.onError("Android 13 or newer is required for local file transcription.");return;}
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)){
            cb.onError("On-device speech recognition is not installed/enabled on this phone.");return;
        }
        List<Diarizer.Segment> usable=new ArrayList<>();
        for(Diarizer.Segment s:segments) if(s.endMs-s.startMs>=900) usable.add(s);
        if(usable.isEmpty()){cb.onError("No usable speech segments detected.");return;}
        transcribeNext(processedWav,sampleRate,usable,0,new StringBuilder(),cb);
    }

    private void transcribeNext(File wav,int sr,List<Diarizer.Segment> segs,int index,StringBuilder out,Callback cb){
        if(index>=segs.size()){cb.onComplete(compact(out.toString()));return;}
        Diarizer.Segment seg=segs.get(index);
        cb.onProgress(index,segs.size(),"Transcribing Speaker "+seg.speaker);
        main.post(()->startOne(wav,sr,seg,new Result(){
            public void done(String text){
                if(text!=null && !text.trim().isEmpty()) out.append("Speaker ").append(seg.speaker).append(":\n").append(text.trim()).append("\n\n");
                transcribeNext(wav,sr,segs,index+1,out,cb);
            }
            public void fail(){ transcribeNext(wav,sr,segs,index+1,out,cb); }
        }));
    }

    private interface Result { void done(String text); void fail(); }

    private void startOne(File wav,int sr,Diarizer.Segment seg,Result result){
        try{
            if(recognizer!=null){recognizer.destroy();recognizer=null;}
            recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            finishedOne=false;
            StringBuilder collected=new StringBuilder();
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            intent.putExtra("android.speech.extra.AUDIO_SOURCE",pipe[0]);
            intent.putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT",1);
            intent.putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING",AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE",sr);
            intent.putExtra("android.speech.extra.SEGMENTED_SESSION","android.speech.extra.AUDIO_SOURCE");
            if(Build.VERSION.SDK_INT>=34){
                intent.putExtra("android.speech.extra.REQUEST_WORD_TIMING",true);
                intent.putExtra("android.speech.extra.REQUEST_WORD_CONFIDENCE",true);
            }
            recognizer.setRecognitionListener(new RecognitionListener(){
                @Override public void onReadyForSpeech(Bundle p){}
                @Override public void onBeginningOfSpeech(){}
                @Override public void onRmsChanged(float r){}
                @Override public void onBufferReceived(byte[] b){}
                @Override public void onEndOfSpeech(){}
                @Override public void onError(int e){ if(!finishedOne){finishedOne=true; cleanup(); result.fail();} }
                @Override public void onResults(Bundle b){append(b,collected);finish();}
                @Override public void onPartialResults(Bundle b){}
                @Override public void onEvent(int e,Bundle b){}
                @Override public void onSegmentResults(Bundle b){append(b,collected);}
                @Override public void onEndOfSegmentedSession(){finish();}
                private void finish(){if(!finishedOne){finishedOne=true;String t=collected.toString().trim();cleanup();result.done(t);}}
                private void cleanup(){try{pipe[0].close();}catch(Exception ignored){} if(recognizer!=null){recognizer.destroy();recognizer=null;}}
            });
            recognizer.startListening(intent);
            new Thread(()->feedSegment(wav,seg,sr,pipe[1]),"GM-AudioFeed").start();
        }catch(Exception e){result.fail();}
    }

    private static void append(Bundle b,StringBuilder sb){
        if(b==null)return; ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if(r!=null&&!r.isEmpty()){if(sb.length()>0)sb.append(' ');sb.append(r.get(0));}
    }

    private static void feedSegment(File wav,Diarizer.Segment seg,int sr,ParcelFileDescriptor writeEnd){
        long startBytes=44+(seg.startMs*sr/1000L)*2L; long bytes=((seg.endMs-seg.startMs)*sr/1000L)*2L;
        try(RandomAccessFile raf=new RandomAccessFile(wav,"r"); FileOutputStream os=new FileOutputStream(writeEnd.getFileDescriptor())){
            raf.seek(Math.min(startBytes,raf.length())); byte[] b=new byte[8192]; long left=Math.min(bytes,Math.max(0,raf.length()-startBytes));
            while(left>0){int n=raf.read(b,0,(int)Math.min(b.length,left));if(n<=0)break;os.write(b,0,n);left-=n;} os.flush();
        }catch(Exception ignored){} finally {try{writeEnd.close();}catch(Exception ignored){}}
    }

    private static String compact(String raw){
        String[] blocks=raw.trim().split("\\n\\n+"); StringBuilder out=new StringBuilder(); int last=-1;
        for(String b:blocks){int c=b.indexOf(':'); if(c<0)continue; String head=b.substring(0,c).trim(),txt=b.substring(c+1).trim(); int sp=-1;try{sp=Integer.parseInt(head.replace("Speaker","").trim());}catch(Exception ignored){}
            if(sp==last && out.length()>0){int p=out.lastIndexOf("\n\n"); if(p>=0) out.insert(p," "+txt); else out.append(' ').append(txt);}
            else {if(out.length()>0)out.append("\n\n");out.append("Speaker ").append(sp).append(":\n").append(txt);last=sp;}
        }
        return out.toString().trim();
    }
}
