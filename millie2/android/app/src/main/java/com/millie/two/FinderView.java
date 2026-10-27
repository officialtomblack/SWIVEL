package com.millie.two;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.View;

/** Full-screen hot/cold finder: smoothed RSSI dial + Geiger-style beep that
 *  speeds up as you approach. Raw RSSI is too jumpy to locate a tag directly,
 *  so a rolling average is used. Call update(rssi) as readings arrive and
 *  stop() when leaving the view. */
final class FinderView extends View {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    final Handler handler=new Handler(Looper.getMainLooper());
    final ToneGenerator tone;
    double smoothed=-100; boolean haveSample; long lastSample; String title;
    static final double ALPHA=0.25; // rolling-average weight
    int bg,accent,dim,white;

    FinderView(Context c,String title,int bg,int accent,int dim,int white){super(c);this.title=title;this.bg=bg;this.accent=accent;this.dim=dim;this.white=white;
        ToneGenerator t;try{t=new ToneGenerator(AudioManager.STREAM_MUSIC,80);}catch(Exception e){t=null;}tone=t;scheduleBeep();}

    void update(int rssi){if(rssi>=0||rssi<-120)return;if(!haveSample){smoothed=rssi;haveSample=true;}else smoothed=ALPHA*rssi+(1-ALPHA)*smoothed;lastSample=SystemClock.elapsedRealtime();postInvalidateOnAnimation();}

    /** 0..1 proximity from smoothed RSSI (−100 far … −40 close). */
    double proximity(){double v=(smoothed+100)/60.0;return Math.max(0,Math.min(1,v));}

    void scheduleBeep(){handler.postDelayed(beep,400);}
    final Runnable beep=new Runnable(){public void run(){
        boolean stale=SystemClock.elapsedRealtime()-lastSample>5000;
        if(tone!=null&&haveSample&&!stale){tone.startTone(ToneGenerator.TONE_PROP_BEEP,60);}
        long interval=(long)(900-800*proximity());if(interval<90)interval=90;
        handler.postDelayed(this,stale?1200:interval);
    }};

    void stop(){handler.removeCallbacks(beep);if(tone!=null)try{tone.release();}catch(Exception ignored){}}

    @Override protected void onDraw(Canvas c){super.onDraw(c);
        float w=getWidth(),h=getHeight(),cx=w/2,cy=h*0.42f,r=Math.min(w,h)*0.32f;
        p.setStyle(Paint.Style.FILL);p.setColor(bg);c.drawRect(0,0,w,h,p);
        p.setTypeface(Typeface.MONOSPACE);p.setColor(dim);p.setTextSize(r*0.14f);
        String t=title==null?"":title;c.drawText(t,cx-p.measureText(t)/2,h*0.12f,p);
        double prox=proximity();
        // outer ring
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(r*0.06f);p.setColor(dim);c.drawCircle(cx,cy,r,p);
        // proximity arc
        p.setColor(accent);RectF arc=new RectF(cx-r,cy-r,cx+r,cy+r);c.drawArc(arc,-90,(float)(360*prox),false,p);
        // filled core scaled by proximity
        p.setStyle(Paint.Style.FILL);p.setColor(accent);c.drawCircle(cx,cy,(float)(r*0.65*prox)+r*0.12f,p);
        // RSSI readout
        p.setColor(white);p.setTextSize(r*0.42f);String val=haveSample?String.valueOf(Math.round(smoothed)):"--";
        c.drawText(val,cx-p.measureText(val)/2,cy+r*0.15f,p);
        p.setColor(dim);p.setTextSize(r*0.14f);c.drawText("dBm (smoothed)",cx-p.measureText("dBm (smoothed)")/2,cy+r*0.42f,p);
        // hint
        String hint=prox>0.8?"VERY CLOSE":prox>0.55?"WARMER":prox>0.3?"GETTING WARMER":"COLD";
        p.setColor(accent);p.setTextSize(r*0.2f);c.drawText(hint,cx-p.measureText(hint)/2,h*0.78f,p);
        if(SystemClock.elapsedRealtime()-lastSample>5000){p.setColor(dim);p.setTextSize(r*0.14f);String s="searching…";c.drawText(s,cx-p.measureText(s)/2,h*0.86f,p);}
    }
}
