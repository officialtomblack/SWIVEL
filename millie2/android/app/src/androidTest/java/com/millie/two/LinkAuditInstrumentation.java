package com.millie.two;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Hardware integration check: requires the user's connected, permitted CYD. */
public final class LinkAuditInstrumentation extends Instrumentation {
    CydLinkService link; boolean pipelineOnly; int mergeChecks; long[] concurrent;
    void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    long[] snapshot() throws Exception {CountDownLatch done=new CountDownLatch(1);long[] result=new long[3];link.worker.post(()->{result[0]=link.received;result[1]=link.reconnects;result[2]=link.live?1:0;done.countDown();});check(done.await(5,TimeUnit.SECONDS),"USB worker responds");return result;}
    long[] awaitLive(long minimumOpens,long minimumLines) throws Exception {long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){long[] s=snapshot();if(s[2]==1&&s[1]>=minimumOpens&&s[0]>minimumLines)return s;Thread.sleep(500);}throw new AssertionError("CYD failed to resume data within 20 seconds");}
    Intent screen(){return new Intent().setClassName(getTargetContext(),"com.millie.two.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);pipelineOnly=arguments!=null&&("public-safety".equals(arguments.getString("mode"))||"merge".equals(arguments.getString("mode")));start();}
    void checkSafetyPipeline() throws Exception {
        MainActivity activity=new MainActivity();MainActivity.Row axon=new MainActivity.Row();axon.kind="BLE ";axon.vendor="LOCAL / RANDOMIZED";axon.oui="LOCAL";
        org.json.JSONObject first=new org.json.JSONObject().put("manufacturer","4C0000,4D0301");
        check(activity.classifyInApp(axon,"ble",first).publicSafety,"Phone manufacturer-block boundaries reach classifier");
        check(activity.classifyInApp(axon,"ble",new org.json.JSONObject()).publicSafety,"Verified identifier retained across sparse packet");
        MainActivity.Row fresh=new MainActivity.Row();fresh.kind="BLE ";fresh.vendor="UNASSIGNED";
        check(!activity.classifyInApp(fresh,"ble",new org.json.JSONObject()).publicSafety,"No identity transfer to another address");
        check(activity.classifyInApp(fresh,"ble",new org.json.JSONObject().put("services","0xFE6C")).publicSafety,"CYD short service reaches classifier");
        MainActivity.Row serviceData=new MainActivity.Row();serviceData.kind="BLE ";serviceData.vendor="UNASSIGNED";
        org.json.JSONObject data=new org.json.JSONObject().put("serviceData",new org.json.JSONObject().put("0000fc81-0000-1000-8000-00805f9b34fb","01"));
        check(activity.classifyInApp(serviceData,"ble",data).publicSafety,"Android service-data UUID reaches classifier");
    }
    void merged(boolean ok,String message){mergeChecks++;check(ok,message);}
    org.json.JSONObject observation(String kind,String source,String mac,long order,long seen)throws Exception{return new org.json.JSONObject().put("t",kind).put("_source",source).put("mac",mac).put("rssi",-65).put("_sampleSession",source).put("_sampleOrder",order).put("_observedAt",seen);}
    void checkMergePipeline() throws Exception {
        MainActivity a=new MainActivity();long now=System.currentTimeMillis();String mac="00:11:22:33:44:55";
        org.json.JSONObject phone=observation("wifi","PHONE","00-11-22-33-44-55",100,now-5000).put("ftype","ACCESS_POINT").put("name","SharedNetwork").put("frequencyMHz",2412).put("capabilities","WPA2");
        a.parse(phone.toString(),false);a.parse(observation("wifi","CYD",mac.toLowerCase(),1,now).put("ftype","data").put("ch",1).toString(),false);
        MainActivity.Row r=a.rows.get("WIFI|"+mac);merged(a.rows.size()==1,"Cross-source MAC format deduplication");merged(r.source.equals("PHONE + CYD"),"Combined source attribution");merged(r.count==2,"Independent receiver readings counted");merged(r.raw.optInt("frequencyMHz")==2412&&r.raw.optString("capabilities").equals("WPA2"),"Phone metadata retained after CYD frame");merged(r.name.equals("SharedNetwork"),"Learned name retained");merged(r.firstSeen==now-5000&&r.lastSeen==now,"True sample ages retained");
        a.parse(phone.toString(),false);merged(r.count==2&&a.signalHistory.size()==2,"Cached Android result does not repeat graph/count");
        a.parse(observation("wifi","CYD",mac,1,now).toString(),false);merged(r.count==2,"Service replay does not repeat a measurement");
        a.parse(observation("wifi","CYD",mac,2,now+1).put("ftype","probe_req").put("ssid","SpectrumSetup-test").toString(),false);merged(r.name.equals("SharedNetwork"),"Probe target does not replace network identity");
        a.parse(observation("wifi","PHONE","00:11:22:33:44:66",101,now).put("name","SharedNetwork").put("ftype","ACCESS_POINT").toString(),false);merged(a.rows.size()==2,"Different BSSIDs with same SSID stay distinct");
        a.parse(observation("ble","PHONE",mac,200,now).put("manufacturer","4D0301").toString(),false);merged(a.rows.size()==3,"WiFi and BLE remain distinct identities");
        a.parse(observation("ble","CYD",mac,3,now).toString(),false);MainActivity.Row b=a.rows.get("BLE |"+mac);merged(b.source.equals("PHONE + CYD")&&b.publicSafety,"BLE sources merge without losing public-safety evidence");
        a.parse(observation("wifi","PHONE",mac,99,now-10000).put("name","Wrong old name").put("ftype","ACCESS_POINT").toString(),false);merged(r.name.equals("SharedNetwork")&&r.count==3,"Older cached sample cannot replace fresh identity");
        a.parse(observation("wifi","PHONE","not-a-mac",1,now).toString(),false);merged(a.rows.size()==3,"Invalid addresses do not become shared records");
        ObservationState age=new ObservationState();age.accept(new org.json.JSONObject().put("t","wifi").put("_source","PHONE").put("ageMs",120000),now);merged(age.lastSeen==now-120000,"Cached WiFi age is not relabeled live");
    }
    void checkSeparatedTracker() throws Exception {
        String apple="4C001219"+"10"+"0123456789ABCDEF0123456789ABCDEF0123456789AB"+"0000";
        SeparatedTracker.Hit a=SeparatedTracker.detect("D1:D2:D3:D4:D5:D6",apple,null);
        check(a!=null&&a.stableId.startsWith("airtag:")&&a.label.contains("SEPARATED"),"AirTag offline payload detected as separated");
        SeparatedTracker.Hit t=SeparatedTracker.detect("AA:BB:CC:DD:EE:FF","",new org.json.JSONObject().put("0000feed-0000-1000-8000-00805f9b34fb","01"));
        check(t!=null&&t.stableId.equals("tile:AA:BB:CC:DD:EE:FF"),"Tile service maps to its static MAC identifier");
        SeparatedTracker.Hit s=SeparatedTracker.detect("11:22:33:44:55:66","",new org.json.JSONObject().put("0000fd5a-0000-1000-8000-00805f9b34fb","60AABBCC"));
        check(s!=null&&s.label.contains("OVERMATURE"),"SmartTag overmature-lost state detected");
        check(SeparatedTracker.detect("11:22:33:44:55:66","4C00091122",null)==null,"Non-separated Apple block ignored");
    }
    void checkVendorTable() throws Exception {
        check(VendorTable.lookup("00:25:DF:11:22:33").spec==VendorTable.Spec.HIGH,"Axon prefix -> HIGH specificity");
        check(VendorTable.lookup("B4:1E:52:AA:BB:CC").org.contains("Flock Safety"),"Flock Safety prefix matched");
        check(VendorTable.lookup("70:B3:D5:1C:5A:BC")!=null&&VendorTable.lookup("70:B3:D5:1C:5A:BC").org.contains("ELSAG"),"MA-S 9-hex matched before the shared 70:B3:D5 parent (longest-prefix-first)");
        check(VendorTable.lookup("00:90:7F:11:22:33")==null,"WatchGuard Technologies false-friend not matched");
        check(VendorTable.lookup("00:04:7D:11:22:33").spec==VendorTable.Spec.MEDIUM,"Motorola Solutions -> MEDIUM");
        check(VendorTable.lookup("DA:11:22:33:44:55")==null,"locally-administered / randomized MAC not attributed");
    }
    void checkClassic() throws Exception {
        MainActivity a=new MainActivity();
        check(a.btMajor(512).equals("Phone"),"Class of Device major 512 -> Phone");
        check(a.btMajor(1024).equals("Audio/Video"),"Class of Device major 1024 -> Audio/Video");
        check(a.btMajor(1792).equals("Wearable"),"Class of Device major 1792 -> Wearable");
        check(a.btType(1).equals("CLASSIC")&&a.btType(3).equals("DUAL"),"BluetoothDevice type decode");
    }
    void checkContinuity() throws Exception {
        check(ContinuityParser.apple("4C000719112233445566778899AABBCCDDEEFF00112233445566778899").contains("AirPods"),"Apple AirPods continuity type parsed");
        check(ContinuityParser.apple("4C000C05AABBCCDDEE").contains("Handoff"),"Apple Handoff type parsed (presence only)");
        check(ContinuityParser.fastPair("0C1234FFEE").contains("0x0C1234"),"Fast Pair 24-bit model id parsed");
        check(ContinuityParser.swiftPair("0600300122").contains("Swift Pair"),"MS Swift Pair device type parsed");
        check(ContinuityParser.apple("4D001234").isEmpty(),"non-Apple manufacturer block ignored");
    }
    void checkLocation() throws Exception {
        LocationTracker lt=new LocationTracker();
        lt.setLocation(java.util.Arrays.asList("aa","bb","cc","dd"));
        check(lt.observe("tag1","AirTag")==null,"First place: no follow alert yet");
        check(lt.observe("tag1","AirTag")==null,"Same place again: still no alert");
        lt.setLocation(java.util.Arrays.asList("ee","ff","gg","hh"));
        check(lt.observe("tag1","AirTag")!=null,"Same id at a second place -> follow alert");
        lt.setLocation(java.util.Arrays.asList("aa","bb","cc","xx"));
        check(lt.current==0,"Overlapping BSSID set resolves to the first place (jitter tolerance)");
        lt.setLocation(java.util.Arrays.asList("zz"));
        check(lt.current==-1&&lt.observe("tag2","Tile")==null,"Too few BSSIDs -> no location context, no alert");
    }
    void checkEvilTwinIE() throws Exception {
        java.util.HashMap<String,ThreatRules.SsidBaseline> base=new java.util.HashMap<>();long now=System.currentTimeMillis();
        // Real evil-twin candidate: same SSID on two GLOBALLY-administered vendor prefixes, same security but
        // DIFFERING IE "shapes" (b carries an extra 221 vendor IE) -> weak flag, not strong.
        MainActivity.Row a=wifiRow("CorpNet","3C:5A:B4:00:00:01","0:,1:0011,48:0100000FAC040100000FAC040100000FAC080000");
        MainActivity.Row b=wifiRow("CorpNet","84:1E:A3:00:00:02","0:,1:0011,48:0100000FAC040100000FAC040100000FAC080000,221:00AABB");
        ThreatRules.evaluate(java.util.Arrays.asList(a,b),base,now);
        check(a.suspect&&b.suspect,"Same SSID on two REAL vendor prefixes with differing IEs -> evil-twin suspect");
        check(a.threat.contains("EVIL-TWIN")||b.threat.contains("EVIL-TWIN"),"Divergent IEs across real vendors -> evil-twin flag");
        check(a.threatEvidence.startsWith("weak"),"Evil-twin on IE divergence alone is WEAK, not strong");
        // Neighbours / ISP fleet / mesh: two global vendors, IDENTICAL IE shapes -> NOT flagged.
        MainActivity.Row c=wifiRow("MeshNet","3C:5A:B4:00:00:03","0:,1:0011,48:0100000FAC040100000FAC040100000FAC080000");
        MainActivity.Row d=wifiRow("MeshNet","84:1E:A3:00:00:04","0:,1:0011,48:0100000FAC040100000FAC040100000FAC080000");
        ThreatRules.evaluate(java.util.Arrays.asList(c,d),base,now);
        check(!c.suspect&&!d.suspect,"Identical IE fingerprints across vendors -> NOT flagged (ISP fleet/mesh)");
        // Spectrum-Mobile case: same SSID on many LOCALLY-ADMINISTERED BSSIDs -> never evil-twin (no vendor).
        MainActivity.Row p=wifiRow("Spectrum Mobile","06:53:D2:00:00:01","0:,1:0011,48:0100000FAC040100000FAC040100000FAC080000");
        MainActivity.Row q=wifiRow("Spectrum Mobile","0A:FA:2B:00:00:02","0:,1:0011,48:0100000FAC040100000FAC040100000FAC080000");
        ThreatRules.evaluate(java.util.Arrays.asList(p,q),base,now);
        check(!p.suspect&&!q.suspect,"Same SSID on locally-administered BSSIDs -> NOT evil-twin (no vendor to compare)");
        // Security downgrade still fires on a real (global) BSSID.
        MainActivity.Row e3=wifiRow("Casa","3C:5A:B4:00:00:05","48:0100000FAC040100000FAC040100000FAC080000");
        ThreatRules.evaluate(java.util.Arrays.asList(e3),base,now);
        MainActivity.Row e2=wifiRow("Casa","3C:5A:B4:00:00:05","48:0100000FAC040100000FAC040100000FAC020000");
        ThreatRules.evaluate(java.util.Arrays.asList(e2),base,now);
        check(e2.suspect&&e2.threat.contains("DOWNGRADE"),"RSN AKM WPA3->WPA2 flagged as security downgrade");
    }
    MainActivity.Row wifiRow(String ssid,String mac,String ies) throws Exception {MainActivity.Row r=new MainActivity.Row();r.kind="WIFI";r.name=ssid;r.mac=mac;r.raw=new org.json.JSONObject().put("informationElements",ies);return r;}
    void checkCorrelation() throws Exception {
        HotspotCorrelator.Identity id=HotspotCorrelator.detect("02:11:22:33:44:56","0:,1:0011,221:0017F206001122334455");
        check(id!=null&&id.btMac.equals("00:11:22:33:44:55"),"Apple hotspot IE 221/0017F2 type-6 -> BT MAC extracted");
        check(id.consistency==HotspotCorrelator.Consistency.STRONG,"BSSID lower octets corroborate (±1) -> STRONG");
        check(HotspotCorrelator.detect("02:11:22:33:44:56","221:0017F206010103010000")==null,"Tetherbug constant payload (I/G or U/L bit set) rejected, no fake identity");
        check(HotspotCorrelator.detect("02:11:22:33:44:56","")==null,"No information elements (e.g. CYD frame) -> not a hotspot");
        check(HotspotCorrelator.detect("02:11:22:33:44:56","221:00163306001122334455")==null,"Non-Apple vendor IE ignored");
        check(HotspotCorrelator.consecutiveMac("00:11:22:33:44:60","00:11:22:33:44:61")!=null,"Consecutive factory MACs -> same-hardware link");
        check(HotspotCorrelator.consecutiveMac("02:11:22:33:44:60","00:11:22:33:44:61")==null,"Locally-administered MAC refused (hard rule)");
        java.util.List<long[]> w=java.util.Arrays.asList(new long[]{1000,-70},new long[]{6000,-60},new long[]{11000,-50});
        java.util.List<long[]> b=java.util.Arrays.asList(new long[]{1500,-80},new long[]{6500,-70},new long[]{11500,-60});
        HotspotCorrelator.Link co=HotspotCorrelator.coPresence(w,b,1000,1500,12000,true);
        check(co!=null&&!co.strong&&co.label.contains("[LOW]"),"Lockstep RSSI co-movement -> weak LOW link, never strong");
        java.util.List<long[]> bAnti=java.util.Arrays.asList(new long[]{1500,-60},new long[]{6500,-70},new long[]{11500,-80});
        check(HotspotCorrelator.coPresence(w,bAnti,1000,1500,12000,true)==null,"Anti-correlated RSSI -> no co-presence link");
    }
    void checkObservation() throws Exception {
        MainActivity a=new MainActivity();
        MainActivity.Row bleRnd=new MainActivity.Row();bleRnd.kind="BLE ";bleRnd.mac="7A:11:22:33:44:55";bleRnd.vendor="RANDOM / PRIVATE";bleRnd.raw=new org.json.JSONObject();
        org.json.JSONObject o1=a.buildObservation(bleRnd);
        check(o1!=null&&o1.optString("address_type").startsWith("random")&&o1.optString("vendor","x").isEmpty(),"Randomized BLE address -> address_type set, NO vendor (hard rule)");
        MainActivity.Row bleNrpa=new MainActivity.Row();bleNrpa.kind="BLE ";bleNrpa.mac="3C:11:22:33:44:55";bleNrpa.vendor="SomeVendor";bleNrpa.raw=new org.json.JSONObject();
        org.json.JSONObject o3=a.buildObservation(bleNrpa);
        check(o3.optString("address_type").equals("random_nrpa")&&o3.optString("vendor","x").isEmpty(),"NRPA (top bits 00, 0x02 clear) -> random_nrpa, NO vendor even with a non-random vendor label");
        MainActivity.Row wifiPub=new MainActivity.Row();wifiPub.kind="WIFI";wifiPub.mac="00:11:22:33:44:55";wifiPub.vendor="Cisco";wifiPub.raw=new org.json.JSONObject().put("capabilities","WPA2").put("frequencyMHz",2412);
        org.json.JSONObject o2=a.buildObservation(wifiPub);
        check(o2.optString("address_type").equals("public")&&o2.optString("vendor").equals("Cisco"),"Public WiFi BSSID -> vendor retained");
        check(o2.optString("security").equals("WPA2")&&o2.optInt("frequency")==2412,"WiFi security + frequency captured");
        MainActivity.Row wifiLaa=new MainActivity.Row();wifiLaa.kind="WIFI";wifiLaa.mac="02:11:22:33:44:55";wifiLaa.vendor="LOCAL / RANDOMIZED";wifiLaa.raw=new org.json.JSONObject();
        check(a.buildObservation(wifiLaa).optString("vendor","x").isEmpty(),"Locally-administered WiFi BSSID -> NO vendor (hard rule)");
        MainActivity.Row btc=new MainActivity.Row();btc.kind="BTC ";btc.mac="00:AA:BB:CC:DD:EE";btc.vendor="Sony";btc.raw=new org.json.JSONObject();
        check(a.buildObservation(btc).optString("kind").equals("CLASSIC"),"Classic kind mapped BTC -> CLASSIC");
        MainActivity.Row wifiIe=new MainActivity.Row();wifiIe.kind="WIFI";wifiIe.mac="00:11:22:33:44:66";wifiIe.vendor="Cisco";wifiIe.raw=new org.json.JSONObject().put("informationElements","0:,221:0017F206").put("channelWidth",2).put("frequencyMHz",5180);
        org.json.JSONObject oie=a.buildObservation(wifiIe);
        check(oie.optString("information_elements").contains("221:")&&oie.optInt("channel_width",-1)==2,"Raw Wi-Fi IEs + channel width persisted to the log row (#7)");
        MainActivity.Row bleTx=new MainActivity.Row();bleTx.kind="BLE ";bleTx.mac="7C:11:22:33:44:55";bleTx.raw=new org.json.JSONObject().put("txPower",-21).put("primaryPhy",1).put("advertisingSid",3);
        org.json.JSONObject otx=a.buildObservation(bleTx);
        check(otx.optInt("tx_power",99)==-21&&otx.optInt("primary_phy",-1)==1&&otx.optInt("advertising_sid",-1)==3,"Raw BLE txPower/PHY/SID persisted to the log row (#7)");
    }
    void checkSessionLog() throws Exception {
        SessionLog sl=new SessionLog(getTargetContext(),true);
        sl.record(new org.json.JSONObject().put("mac","A").put("kind","WIFI"),true);
        sl.record(new org.json.JSONObject().put("mac","B").put("kind","BLE"),false);   // log-everything on -> kept
        sl.logEverything=false;
        sl.record(new org.json.JSONObject().put("mac","C").put("kind","BLE"),false);   // flagged-only -> dropped
        sl.record(new org.json.JSONObject().put("mac","D").put("kind","WIFI"),true);   // flagged -> kept
        sl.stop();
        int lines=0; boolean sawC=false;
        try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.FileReader(sl.file))){String ln;while((ln=r.readLine())!=null){if(!ln.trim().isEmpty())lines++;if(ln.contains("\"C\""))sawC=true;}}
        check(lines==3,"NDJSON persisted every captured observation and only those (3 of 4)");
        check(!sawC,"Flagged-only toggle dropped the unflagged observation");
        org.json.JSONObject wo=new org.json.JSONObject().put("kind","WIFI").put("mac","3C:5A:B4:11:22:33").put("ssid_or_name","Net,comma").put("security","[WPA2]").put("iso_time","2026-10-29T19:34:25-0400").put("channel",36).put("rssi",-50).put("lat",40.0).put("lon",-75.0);
        String wline=SessionLog.wigle(wo);
        check(wline.startsWith("3C:5A:B4:11:22:33,")&&wline.contains("\"Net,comma\"")&&wline.trim().endsWith("\"WIFI\""),"WigleCSV row: bare MAC, quoted comma-SSID, WIFI type");
        sl.file.delete();
    }
    long[] scanState(MainActivity a){long[] state=new long[9];runOnMainSync(()->{state[0]=a.phoneActive?1:0;state[1]=a.phoneBleActive?1:0;state[2]=a.phoneWifiAccepted;state[3]=a.phoneBleAccepted;state[4]=a.cydWifiAccepted;state[5]=a.cydBleAccepted;state[6]=a.wifiScanRequests;for(MainActivity.Row row:a.snapshot())if(row.observations.sources.size()>1)state[7]++;state[8]=a.gotAlive?1:0;});return state;}
    void checkConcurrentScan(MainActivity a)throws Exception{long[] before=scanState(a);long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){Thread.sleep(1000);long[] current=scanState(a);if(current[3]>before[3]&&current[4]+current[5]>before[4]+before[5]&&current[6]>before[6])break;}long[] after=scanState(a);check(after[0]==1&&after[1]==1&&after[8]==1,"Phone BLE scanner and CYD both active");check(after[3]>before[3],"Real phone BLE observations continue while CYD attached");check(after[4]+after[5]>before[4]+before[5],"Real CYD observations continue alongside phone");check(after[6]>before[6],"Android WiFi scan timer continues alongside CYD");check(after[2]>0,"Phone WiFi results are present");concurrent=new long[]{after[2]-before[2],after[3]-before[3],after[4]+after[5]-before[4]-before[5],after[7]};}
    @Override public void onStart(){Bundle result=new Bundle();ServiceConnection connection=null;try{
        final Throwable[] pipelineError=new Throwable[1];runOnMainSync(()->{try{checkSafetyPipeline();checkMergePipeline();checkSeparatedTracker();checkVendorTable();checkContinuity();checkClassic();checkLocation();checkEvilTwinIE();checkCorrelation();checkObservation();checkSessionLog();}catch(Throwable error){pipelineError[0]=error;}});if(pipelineError[0]!=null)throw new AssertionError("Public-safety pipeline: "+pipelineError[0],pipelineError[0]);if(pipelineOnly){result.putString("result","PASS: public-safety, dual-source merge, separated-tracker, vendor-table, continuity, classic, location, and evil-twin-IE checks on device; USB hardware tests not requested");finish(Activity.RESULT_OK,result);return;}
        Activity first=startActivitySync(screen());CountDownLatch bound=new CountDownLatch(1);
        connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder binder){link=((CydLinkService.LocalBinder)binder).service();bound.countDown();}public void onServiceDisconnected(ComponentName n){}};
        getTargetContext().bindService(new Intent(getTargetContext(),CydLinkService.class),connection,Context.BIND_AUTO_CREATE);check(bound.await(10,TimeUnit.SECONDS),"Service binds");long[] initial=awaitLive(1,0);checkConcurrentScan((MainActivity)first);initial=snapshot();
        runOnMainSync(first::finish);Thread.sleep(6000);long[] background=snapshot();check(background[2]==1&&background[0]>initial[0],"CYD continues receiving after Activity finishes");check(background[1]==initial[1],"Closing Activity does not reopen USB");
        startActivitySync(screen());Thread.sleep(2000);check(snapshot()[1]==initial[1],"Reopening screen reuses USB connection");
        long[] before=snapshot();link.worker.post(()->link.failed("audit simulated read failure"));long[] recovered=awaitLive(before[1]+1,before[0]);check(recovered[1]==before[1]+1,"One failure creates exactly one replacement reader");
        getTargetContext().startForegroundService(new Intent(getTargetContext(),CydLinkService.class));getTargetContext().startForegroundService(new Intent(getTargetContext(),CydLinkService.class));Thread.sleep(4000);long[] stable=snapshot();check(stable[2]==1&&stable[1]==recovered[1],"Repeated connect requests retain one live reader");check(stable[0]>recovered[0],"Data continues after recovery");
        result.putLongArray("concurrent_scan_deltas_wifi_ble_cyd_shared",concurrent);result.putInt("merge_checks",mergeChecks);result.putString("result","PASS: phone WiFi/BLE plus CYD concurrent scan; dual-source merge; 5 public-safety pipeline checks; Activity close/reopen, live background stream, simulated serial failure recovery, and duplicate-connect protection");result.putLong("lines",stable[0]);result.putLong("opens",stable[1]);finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}finally{if(connection!=null)try{getTargetContext().unbindService(connection);}catch(Exception ignored){}}}
}
