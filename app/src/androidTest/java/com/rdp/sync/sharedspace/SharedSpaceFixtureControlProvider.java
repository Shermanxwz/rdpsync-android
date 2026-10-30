package com.rdp.sync.sharedspace;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Test APK only. Explicitly UID-gated owner trampoline, not a permission
 * bypass in production. Instrumentation runs with the target app's UID; this
 * provider invokes the separate DocumentsProvider with its actual owner UID
 * so the fixture can issue/revoke real grants without assuming that Android's
 * shell possesses the signature-only MANAGE_DOCUMENTS permission. */
public final class SharedSpaceFixtureControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("fixture.")) throw new UnsupportedOperationException();
        boolean allowed = Binder.getCallingUid() == getContext().getApplicationInfo().uid;
        for (InstrumentationInfo info : getContext().getPackageManager().queryInstrumentation(null, 0)) {
            if (!getContext().getPackageName().equals(info.packageName)) continue;
            try { allowed |= Binder.getCallingUid() == getContext().getPackageManager().getApplicationInfo(info.targetPackage, 0).uid; }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        if (!allowed) throw new SecurityException("Synthetic fixture target only");
        long identity = Binder.clearCallingIdentity();
        try {
            Uri documents = Uri.parse("content://" + getContext().getPackageName() + ".sharedspacefixture.documents");
            return getContext().getContentResolver().call(documents, method, arg, extras);
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
