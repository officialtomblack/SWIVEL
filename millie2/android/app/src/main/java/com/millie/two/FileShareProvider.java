package com.millie.two;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;

/** Minimal content provider so exported CSV/KML can be shared via ACTION_SEND
 *  without the androidx FileProvider dependency. Serves files from the app's
 *  external files dir read-only. */
public final class FileShareProvider extends ContentProvider {
    static String authority(Context c){return c.getPackageName()+".fileshare";}
    static Uri uriFor(Context c,File f){return new Uri.Builder().scheme("content").authority(authority(c)).path(f.getName()).build();}

    File resolve(Uri uri){File dir=getContext().getExternalFilesDir(null);if(dir==null)dir=getContext().getFilesDir();String name=new File(uri.getPath()).getName();return new File(dir,name);}

    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{
        File f=resolve(uri);if(!f.exists())throw new java.io.FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        File f=resolve(uri);String[] cols={OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor c=new MatrixCursor(cols);c.addRow(new Object[]{f.getName(),f.length()});return c;
    }
    @Override public String getType(Uri uri){String n=uri.getPath()==null?"":uri.getPath();if(n.endsWith(".csv"))return "text/csv";if(n.endsWith(".kml"))return "application/vnd.google-earth.kml+xml";return "application/octet-stream";}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String selection,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
