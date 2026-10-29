package com.millie.two;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import org.json.JSONObject;
import java.util.*;

/** Foreground BLE scan that keeps logging while the screen is off.
 *  Android stops catch-all (null-filter) scans on screen-off, so this service
 *  passes non-null ScanFilters built from the tracker + public-safety signatures. */
public final class ScanService extends Service {
    static final String CHANNEL="scan_bg", STOP="com.millie.civops2.STOP_SCAN";
    interface Listener { void onScan(String json); }
    final class LocalBinder extends Binder { ScanService service(){return ScanService.this;} }
    final Handler main=new Handler(Looper.getMainLooper());
    final HashSet<Listener> listeners=new HashSet<>();
    BluetoothLeScanner scanner; ScanCallback callback; boolean scanning; String state="idle";
    PowerManager.WakeLock cpu;
    LocationManager locationManager; LocationListener locationListener;
    static volatile double lastLat=Double.NaN,lastLon=Double.NaN; static volatile long lastFixAt; static volatile float lastAccuracy=-1;
    static boolean hasFix(){return !Double.isNaN(lastLat)&&!Double.isNaN(lastLon);}
    /** 16-bit service UUIDs matched both as advertised service UUIDs and as service-data keys. */
    static final String[] SHORT_UUIDS={"feaa","fe33","fa25","fd5a","fd69","feec","feed","fe6b","fe6c","fc81","fe04","fd8e"};
    /** 128-bit service UUIDs (Flock GATT + Nordic DFU). */
    static final String[] LONG_UUIDS={"e8ccbb38-9532-46a8-9fe5-1814df172e6f","00001530-1212-efde-1523-785feabcd123"};
    /** Manufacturer company IDs: Apple (Find My) and XUNTONG (Flock radio). */
    static final int[] COMPANY_IDS={0x004C,0x09C8};

    @Override public void onCreate(){super.onCreate();
        cpu=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SWIVEL:SCAN");cpu.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Background scan",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){stopScan();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        Notification n=notification(state);
        int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;if(Build.VERSION.SDK_INT>=34&&hasLocationPermission())type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if(Build.VERSION.SDK_INT>=29)startForeground(21,n,type);else startForeground(21,n);
        startScan();startLocation();return START_STICKY;}
    boolean hasLocationPermission(){return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    @Override public IBinder onBind(Intent intent){return new LocalBinder();}
    @Override public void onDestroy(){stopScan();stopLocation();if(cpu!=null&&cpu.isHeld())cpu.release();super.onDestroy();}

    void register(Listener l){listeners.add(l);} void unregister(Listener l){listeners.remove(l);}

    Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScanService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_millie_status).setContentTitle("SWIVEL · background scan").setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();}

    static android.os.ParcelUuid shortUuid(String s){return android.os.ParcelUuid.fromString("0000"+s+"-0000-1000-8000-00805F9B34FB");}
    List<ScanFilter> filters(){
        ArrayList<ScanFilter> out=new ArrayList<>();
        for(String s:SHORT_UUIDS){android.os.ParcelUuid u=shortUuid(s);out.add(new ScanFilter.Builder().setServiceUuid(u).build());out.add(new ScanFilter.Builder().setServiceData(u,new byte[]{}).build());}
        for(String s:LONG_UUIDS)out.add(new ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid.fromString(s)).build());
        for(int id:COMPANY_IDS)out.add(new ScanFilter.Builder().setManufacturerData(id,new byte[]{}).build());
        return out;}

    void startScan(){
        try{
            BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter a=bm==null?null:bm.getAdapter();
            scanner=a==null?null:a.getBluetoothLeScanner();
            if(scanner==null){state="Bluetooth unavailable";return;}
            if(callback!=null)try{scanner.stopScan(callback);}catch(Exception ignored){}
            callback=new ScanCallback(){
                @Override public void onScanResult(int type,ScanResult r){emit(type,r);}
                @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult r:results)emit(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,r);}
                @Override public void onScanFailed(int error){scanning=false;state="scan failed "+error;}
            };
            ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build();
            scanner.startScan(filters(),settings,callback);scanning=true;state="filtered scan active";
            if(!cpu.isHeld())cpu.acquire();
        }catch(SecurityException e){state="permission denied";}catch(Exception e){state="scan unavailable";}
    }
    void stopScan(){scanning=false;if(scanner!=null&&callback!=null)try{scanner.stopScan(callback);}catch(Exception ignored){}if(cpu!=null&&cpu.isHeld())cpu.release();}

    void startLocation(){
        if(!hasLocationPermission())return;
        try{
            locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);if(locationManager==null)return;
            Location last=null;for(String p:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER}){try{Location l=locationManager.getLastKnownLocation(p);if(l!=null&&(last==null||l.getTime()>last.getTime()))last=l;}catch(Exception ignored){}}
            if(last!=null)noteFix(last);
            if(locationListener==null)locationListener=new LocationListener(){public void onLocationChanged(Location l){noteFix(l);}public void onProviderEnabled(String p){}public void onProviderDisabled(String p){}public void onStatusChanged(String p,int s,Bundle e){}};
            String provider=locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?LocationManager.NETWORK_PROVIDER:LocationManager.GPS_PROVIDER;
            locationManager.requestLocationUpdates(provider,60000L,25f,locationListener,Looper.getMainLooper());
            state=scanning?"filtered scan + location":state;
        }catch(SecurityException ignored){}catch(Exception ignored){}
    }
    void stopLocation(){if(locationManager!=null&&locationListener!=null)try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}}
    static void noteFix(Location l){lastLat=l.getLatitude();lastLon=l.getLongitude();lastFixAt=System.currentTimeMillis();lastAccuracy=l.hasAccuracy()?l.getAccuracy():-1;}

    void emit(int type,ScanResult r){
        try{
            JSONObject j=new JSONObject();j.put("t","ble");j.put("_source","PHONE");j.put("mac",r.getDevice().getAddress());j.put("rssi",r.getRssi());j.put("callbackType",type);
            if(hasFix()){j.put("_lat",lastLat);j.put("_lon",lastLon);j.put("_fixAt",lastFixAt);if(lastAccuracy>=0)j.put("_acc",lastAccuracy);}
            j.put("_observedAt",System.currentTimeMillis()-Math.max(0,(SystemClock.elapsedRealtimeNanos()-r.getTimestampNanos())/1000000));
            ScanRecord rec=r.getScanRecord();
            if(rec!=null){
                String name=rec.getDeviceName();if(name!=null)j.put("name",name);
                StringBuilder m=new StringBuilder();android.util.SparseArray<byte[]> d=rec.getManufacturerSpecificData();
                for(int i=0;i<d.size();i++){byte[] v=d.valueAt(i);int id=d.keyAt(i);byte[] full=new byte[v.length+2];full[0]=(byte)(id&255);full[1]=(byte)((id>>8)&255);System.arraycopy(v,0,full,2,v.length);if(m.length()>0)m.append(",");m.append(hex(full));}
                j.put("manufacturer",m.toString());
                ArrayList<String> uuids=new ArrayList<>();if(rec.getServiceUuids()!=null)for(android.os.ParcelUuid u:rec.getServiceUuids())uuids.add(u.toString().replace("-",""));
                JSONObject serviceData=new JSONObject();for(Map.Entry<android.os.ParcelUuid,byte[]> e:rec.getServiceData().entrySet()){serviceData.put(e.getKey().toString(),hex(e.getValue()));uuids.add(e.getKey().toString().replace("-",""));}
                j.put("services",String.join(",",uuids));j.put("serviceData",serviceData);
            }
            final String line=j.toString();main.post(()->{for(Listener l:new ArrayList<>(listeners))l.onScan(line);});
        }catch(Exception ignored){}
    }
    static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte x:data)b.append(String.format(Locale.US,"%02X",x&255));return b.toString();}
}
