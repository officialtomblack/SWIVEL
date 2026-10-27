package com.millie.two;

import java.util.*;

/** Phone-observable rogue-AP / collector heuristics. Strong/weak evidence only,
 *  never a certainty claim. A receive-only sniffer emits nothing and cannot be
 *  detected here; these rules target the ACTIVE footprint an attacker leaves. */
final class ThreatRules {
    static final class Flag {
        final String label,evidence; final boolean suspect,collector,strong;
        Flag(String label,String evidence,boolean suspect,boolean collector,boolean strong){this.label=label;this.evidence=evidence;this.suspect=suspect;this.collector=collector;this.strong=strong;}
    }
    /** Per-SSID baseline kept across the session (and persistable by the caller). */
    static final class SsidBaseline { String ssid=""; final HashSet<String> ouis=new HashSet<>(); String security=""; long firstSeen,lastSeen; }

    static String ssidOf(MainActivity.Row r){return r.name==null?"":r.name.trim();}
    static String ouiOf(MainActivity.Row r){String h=r.mac==null?"":r.mac.replace(":","").toUpperCase(Locale.ROOT);return h.length()>=6?h.substring(0,6):"";}

    /** Coarse security tier from Wi-Fi capabilities string. */
    static String securityTier(String capabilities){
        String c=capabilities==null?"":capabilities.toUpperCase(Locale.ROOT);
        if(c.contains("WPA3")||c.contains("SAE"))return "WPA3";
        if(c.contains("WPA2")||c.contains("RSN"))return "WPA2";
        if(c.contains("WPA"))return "WPA";
        if(c.contains("WEP"))return "WEP";
        return "OPEN";
    }
    static int tierRank(String t){switch(t){case "WPA3":return 4;case "WPA2":return 3;case "WPA":return 2;case "WEP":return 1;default:return 0;}}

    /** 2.5c — known pentest-gear SSID/name signatures. */
    static Flag hardwareFingerprint(MainActivity.Row r){
        String n=ssidOf(r).toLowerCase(Locale.ROOT);
        if(n.isEmpty())return null;
        if(n.equals("pineapple")||n.startsWith("pineapple_")||n.equals("wifipineapple"))
            return new Flag("COLLECTOR · WIFI PINEAPPLE","strong · default SSID "+ssidOf(r),true,true,true);
        if(n.startsWith("marauder")||n.contains("esp32 marauder"))
            return new Flag("COLLECTOR · ESP32 MARAUDER","strong · beacon SSID "+ssidOf(r),true,true,true);
        if(n.equals("flipper")||n.startsWith("flipper "))
            return new Flag("COLLECTOR · FLIPPER ZERO","weak · advertised name "+ssidOf(r),true,true,false);
        for(String d:new String[]{"linksys","netgear","default","free wifi","free_wifi","xfinitywifi","attwifi"})
            if(n.equals(d))return new Flag("SUSPECT · COMMON ROGUE SSID","weak · generic/open default "+ssidOf(r),true,false,false);
        return null;
    }

    /** Evaluate every row against the current scan + persisted baselines.
     *  Writes suspect/collector flags back onto each Row and returns rows newly flagged. */
    static List<MainActivity.Row> evaluate(List<MainActivity.Row> rows,Map<String,SsidBaseline> baselines,long now){
        HashMap<String,HashSet<String>> ssidOuis=new HashMap<>();
        HashMap<String,HashSet<String>> ouiSsids=new HashMap<>();
        HashMap<String,String> ssidSecurity=new HashMap<>();
        for(MainActivity.Row r:rows){
            if(!r.kind.trim().equals("WIFI"))continue;
            String ssid=ssidOf(r);if(ssid.isEmpty()||ssid.equals("<unknown ssid>"))continue;
            String o=ouiOf(r);if(o.isEmpty())continue;
            ssidOuis.computeIfAbsent(ssid,k->new HashSet<>()).add(o);
            ouiSsids.computeIfAbsent(o,k->new HashSet<>()).add(ssid);
            String tier=securityTier(r.raw==null?"":r.raw.optString("capabilities",""));
            String prev=ssidSecurity.get(ssid);if(prev==null||tierRank(tier)<tierRank(prev))ssidSecurity.put(ssid,tier);
        }
        ArrayList<MainActivity.Row> newlyFlagged=new ArrayList<>();
        for(MainActivity.Row r:rows){
            boolean wasSuspect=r.suspect,wasCollector=r.collector;String label=null,evidence=null;boolean suspect=false,collector=false;
            Flag hw=hardwareFingerprint(r);
            if(hw!=null){label=hw.label;evidence=hw.evidence;suspect=hw.suspect;collector=hw.collector;}
            if(r.kind.trim().equals("WIFI")){
                String ssid=ssidOf(r);String o=ouiOf(r);
                if(!ssid.isEmpty()&&!ssid.equals("<unknown ssid>")&&!o.isEmpty()){
                    // 2.3 / 2.5b evil-twin: same SSID on >1 OUI
                    HashSet<String> ouis=ssidOuis.get(ssid);
                    if(ouis!=null&&ouis.size()>1){suspect=true;if(label==null){label="SUSPECT · EVIL-TWIN";evidence="weak · SSID '"+ssid+"' seen on "+ouis.size()+" vendor prefixes (also a normal ISP-gateway + extender setup)";}}
                    // 2.5a karma/pineapple: one OUI presenting many SSIDs
                    HashSet<String> ssids=ouiSsids.get(o);
                    if(ssids!=null&&ssids.size()>=6){collector=true;suspect=true;label="COLLECTOR · KARMA/PINEAPPLE";evidence="strong · vendor prefix "+o+" presents "+ssids.size()+" SSIDs";}
                    // 2.5b security downgrade vs baseline
                    SsidBaseline base=baselines.get(ssid);
                    String tier=securityTier(r.raw==null?"":r.raw.optString("capabilities",""));
                    if(base!=null&&!base.security.isEmpty()&&tierRank(tier)<tierRank(base.security)){suspect=true;if(label==null||!collector){label="SUSPECT · SECURITY DOWNGRADE";evidence="strong · '"+ssid+"' "+base.security+" → "+tier;}}
                    // update baseline
                    if(base==null){base=new SsidBaseline();base.ssid=ssid;base.security=tier;base.firstSeen=now;baselines.put(ssid,base);}
                    base.ouis.add(o);base.lastSeen=now;if(tierRank(tier)>tierRank(base.security)||base.security.isEmpty())base.security=tier;
                }
            }
            r.suspect=suspect;r.collector=collector;r.threat=label==null?"":label;r.threatEvidence=evidence==null?"":evidence;
            if((suspect&&!wasSuspect)||(collector&&!wasCollector))newlyFlagged.add(r);
        }
        return newlyFlagged;
    }

    /** 2.5d BLE spam/swarm: many short-lived advertising addresses from one vendor/company in a burst.
     *  Returns a flag describing the burst, or null. */
    static Flag bleSwarm(int distinctAddrsLastMinute){
        if(distinctAddrsLastMinute>=25)return new Flag("COLLECTOR · BLE AD SWARM","strong · "+distinctAddrsLastMinute+" new BLE addresses in 60s (Flipper/Fast-Pair spam)",true,true,true);
        if(distinctAddrsLastMinute>=12)return new Flag("SUSPECT · BLE AD BURST","weak · "+distinctAddrsLastMinute+" new BLE addresses in 60s",true,false,false);
        return null;
    }
}
