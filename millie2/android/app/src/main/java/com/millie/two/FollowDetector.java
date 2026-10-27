package com.millie.two;

import org.json.JSONObject;
import java.util.*;

/** Follow detection modelled on AirGuard: a device is "following" if the same
 *  BLE identity is seen at >= DISTINCT_PLACES distinct locations over >= MIN_SPAN_MS.
 *  Keyed on a stable service-data payload where one exists, else the MAC. LIMITATION:
 *  trackers that rotate BOTH their MAC and payload (Apple Find My / AirTag in separated
 *  mode) get a fresh key each rotation, so the follow alert never accumulates enough
 *  places to fire on them — they still appear as trackers, just without the follow alert. */
final class FollowDetector {
    static final int DISTINCT_PLACES=3; static final long MIN_SPAN_MS=30*60*1000L;
    static final double PLACE_METERS=80; static final long PLACE_EXPIRY_MS=6*60*60*1000L;

    static final class Place { final double lat,lon; long at; Place(double lat,double lon,long at){this.lat=lat;this.lon=lon;this.at=at;} }
    static final class Track { String key,label; long firstSeen,lastSeen,alertedAt; boolean muted; final ArrayList<Place> places=new ArrayList<>(); }

    final HashMap<String,Track> tracks=new HashMap<>();
    final HashSet<String> safeList=new HashSet<>();

    /** Stable key for a tracker: prefer service-data payload (survives MAC rotation), else MAC. */
    static String identityKey(String mac,JSONObject raw){
        if(raw!=null){JSONObject sd=raw.optJSONObject("serviceData");
            if(sd!=null){Iterator<String> it=sd.keys();String best=null;while(it.hasNext()){String u=it.next();String v=sd.optString(u,"");if(v.length()>=8){String cand=u.replace("-","").toLowerCase(Locale.ROOT)+":"+v.toUpperCase(Locale.ROOT);if(best==null||cand.length()>best.length())best=cand;}}if(best!=null)return "payload:"+best;}
        }
        return "mac:"+(mac==null?"":mac.toUpperCase(Locale.ROOT));
    }

    static double haversine(double lat1,double lon1,double lat2,double lon2){
        double r=6371000,dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2*r*Math.asin(Math.min(1,Math.sqrt(a)));
    }

    /** Record a tracker sighting with a location. Returns an alert string when the
     *  follow threshold is newly crossed (and not muted/safe), else null. */
    String observe(String key,String label,double lat,double lon,long now){
        if(key==null||safeList.contains(key))return null;
        Track t=tracks.get(key);if(t==null){t=new Track();t.key=key;t.firstSeen=now;tracks.put(key,t);}
        t.label=label;t.lastSeen=now;
        t.places.removeIf(p->now-p.at>PLACE_EXPIRY_MS);
        boolean merged=false;for(Place p:t.places)if(haversine(p.lat,p.lon,lat,lon)<=PLACE_METERS){p.at=now;merged=true;break;}
        if(!merged)t.places.add(new Place(lat,lon,now));
        if(t.muted)return null;
        long span=t.lastSeen-t.firstSeen;
        if(t.places.size()>=DISTINCT_PLACES&&span>=MIN_SPAN_MS&&now-t.alertedAt>MIN_SPAN_MS){
            t.alertedAt=now;long mins=span/60000;
            return (t.label==null||t.label.isEmpty()?"Tracker":t.label)+" has been near you at "+t.places.size()+" locations over "+mins+" min.";
        }
        return null;
    }
    void mute(String key){Track t=tracks.get(key);if(t!=null)t.muted=true;}
    void markSafe(String key){if(key!=null){safeList.add(key);tracks.remove(key);}}
    Track track(String key){return tracks.get(key);}
}
