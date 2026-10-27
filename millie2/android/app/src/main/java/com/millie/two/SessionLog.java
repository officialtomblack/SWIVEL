package com.millie.two;

import android.content.Context;
import java.io.*;
import java.util.*;

/** Append-only session log of classified detections with GPS + timestamp.
 *  Exports CSV (spreadsheet) and KML (map). Supports a duress/clear-all wipe. */
final class SessionLog {
    static final class Entry {
        final long time; final String kind,mac,vendor,classification,name; final int rssi; final double lat,lon;
        Entry(long time,String kind,String mac,String vendor,String classification,String name,int rssi,double lat,double lon){this.time=time;this.kind=kind;this.mac=mac;this.vendor=vendor;this.classification=classification;this.name=name;this.rssi=rssi;this.lat=lat;this.lon=lon;}
        boolean hasLocation(){return !Double.isNaN(lat)&&!Double.isNaN(lon);}
    }
    final ArrayList<Entry> entries=new ArrayList<>();
    static final int CAP=5000;
    /** De-dupe repeated rows: one entry per mac per DEDUP_MS. */
    static final long DEDUP_MS=30000;
    final HashMap<String,Long> lastLogged=new HashMap<>();

    synchronized void add(Entry e){
        Long prev=lastLogged.get(e.mac);
        if(prev!=null&&e.time-prev<DEDUP_MS)return;
        lastLogged.put(e.mac,e.time);
        entries.add(e);while(entries.size()>CAP)entries.remove(0);
    }
    synchronized int size(){return entries.size();}
    synchronized void clear(){entries.clear();lastLogged.clear();}

    static String csvEscape(String s){
        if(s==null)return "";
        String v=s;
        // neutralize spreadsheet formula injection: an attacker-controlled device name like =HYPERLINK(...) would run on open
        if(!v.isEmpty()){char c=v.charAt(0);if(c=='='||c=='+'||c=='-'||c=='@'||c=='\t'||c=='\r')v="'"+v;}
        v=v.replace("\"","\"\"");
        return "\""+v+"\"";
    }

    synchronized String toCsv(){
        StringBuilder b=new StringBuilder("timestamp_iso,epoch_ms,kind,mac,vendor,classification,name,rssi,lat,lon\n");
        for(Entry e:entries){
            b.append(csvEscape(iso(e.time))).append(',').append(e.time).append(',')
             .append(csvEscape(e.kind)).append(',').append(csvEscape(e.mac)).append(',')
             .append(csvEscape(e.vendor)).append(',').append(csvEscape(e.classification)).append(',')
             .append(csvEscape(e.name)).append(',').append(e.rssi).append(',')
             .append(e.hasLocation()?e.lat:"").append(',').append(e.hasLocation()?e.lon:"").append('\n');
        }
        return b.toString();
    }
    synchronized String toKml(){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>MILLIE session</name>\n");
        for(Entry e:entries){
            if(!e.hasLocation())continue;
            String title=xml((e.name==null||e.name.isEmpty()?e.vendor:e.name)+" ("+e.kind.trim()+")");
            String desc=xml(e.mac+" · "+e.classification+" · "+e.rssi+" dBm · "+iso(e.time));
            b.append("<Placemark><name>").append(title).append("</name><description>").append(desc)
             .append("</description><Point><coordinates>").append(e.lon).append(',').append(e.lat).append(",0</coordinates></Point></Placemark>\n");
        }
        b.append("</Document></kml>\n");return b.toString();
    }
    static String iso(long t){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US);return f.format(new Date(t));}
    static String xml(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    /** Writes both exports into the app external files dir and returns their paths. */
    synchronized String[] export(Context ctx)throws IOException{
        File dir=ctx.getExternalFilesDir(null);if(dir==null)dir=ctx.getFilesDir();
        String stamp=new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
        File csv=new File(dir,"millie_"+stamp+".csv");File kml=new File(dir,"millie_"+stamp+".kml");
        try(Writer w=new OutputStreamWriter(new FileOutputStream(csv))){w.write(toCsv());}
        try(Writer w=new OutputStreamWriter(new FileOutputStream(kml))){w.write(toKml());}
        return new String[]{csv.getAbsolutePath(),kml.getAbsolutePath()};
    }

    /** Duress wipe: delete every exported CSV/KML left in the files dir, so a cleared
     *  session leaves no GPS trail on disk. */
    static void wipeExports(Context ctx){
        File dir=ctx.getExternalFilesDir(null);if(dir==null)dir=ctx.getFilesDir();
        File[] files=dir==null?null:dir.listFiles((d,n)->n.startsWith("millie_")&&(n.endsWith(".csv")||n.endsWith(".kml")));
        if(files!=null)for(File f:files)f.delete();
    }
}
