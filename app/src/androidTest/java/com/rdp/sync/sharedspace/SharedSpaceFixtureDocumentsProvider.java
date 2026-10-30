package com.rdp.sync.sharedspace;

import android.content.pm.ProviderInfo;
import android.content.pm.InstrumentationInfo;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import com.rdp.sync.transfer.TransferFixtureDocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/** Test APK only, a separate real Binder process. Java/framework only, so no
 * accidental dependency on the target APK's Kotlin runtime. Fixture controls
 * are reached through an androidTest-only UID-gated owner trampoline. The
 * document endpoint remains protected by MANAGE_DOCUMENTS and real URI grants. */
public final class SharedSpaceFixtureDocumentsProvider extends DocumentsProvider {
    private TransferFixtureDocumentsProvider delegate;
    private boolean unknownSize;
    private boolean virtualSource;

    @Override public boolean onCreate() {
        delegate = new TransferFixtureDocumentsProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = getContext().getPackageName() + ".sharedspacefixture.documents";
        info.exported = true; info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        delegate.attachInfo(getContext(), info);
        seed();
        return true;
    }
    private void seed() {
        try {
            delegate.seed("source", "synthetic.bin", payload());
            delegate.seed("original", "keep.bin", new byte[] {9, 8, 7});
            delegate.seed("outside", "outside.bin", new byte[0]);
        } catch (IOException error) { throw new IllegalStateException("Cannot seed synthetic fixture"); }
    }
    public static byte[] payload() {
        byte[] result = new byte[131073];
        for (int i = 0; i < result.length; i++) result[i] = (byte)(i * 31 + 7);
        return result;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("fixture.")) return super.call(method, arg, extras);
        String targetPackage = null;
        for (InstrumentationInfo info : getContext().getPackageManager().queryInstrumentation(null, 0)) {
            if (getContext().getPackageName().equals(info.packageName)) { targetPackage = info.targetPackage; break; }
        }
        if (targetPackage == null) throw new SecurityException("Missing test target");
        try {
            int targetUid = getContext().getPackageManager().getApplicationInfo(targetPackage, 0).uid;
            if (Binder.getCallingUid() != getContext().getApplicationInfo().uid && Binder.getCallingUid() != targetUid)
                throw new SecurityException("Fixture test target only");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException("Missing test target"); }
        if ("fixture.grant".equals(method)) {
            getContext().grantUriPermission(targetPackage, DocumentsContract.buildTreeDocumentUri(
                getContext().getPackageName() + ".sharedspacefixture.documents", "root"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        } else if ("fixture.revoke".equals(method)) {
            getContext().revokeUriPermission(DocumentsContract.buildTreeDocumentUri(
                getContext().getPackageName() + ".sharedspacefixture.documents", "root"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } else if ("fixture.reset".equals(method)) {
            delegate.cleanup(); delegate.onCreate(); seed(); delegate.writes = 0;
            delegate.forceExisting = delegate.forceOutsideChild = false;
            unknownSize = virtualSource = false;
        } else if ("fixture.configure".equals(method)) {
            if (extras == null) throw new IllegalArgumentException("Missing fixture options");
            delegate.forceExisting = extras.getBoolean("existing");
            delegate.forceOutsideChild = extras.getBoolean("outside");
            unknownSize = extras.getBoolean("unknownSize");
            virtualSource = extras.getBoolean("virtual");
        } else if (!"fixture.inspect".equals(method)) throw new UnsupportedOperationException();
        Bundle result = new Bundle();
        result.putInt("pid", Process.myPid()); result.putInt("writes", delegate.writes);
        result.putStringArrayList("ids", new ArrayList<>(delegate.ids()));
        try { result.putByteArray("original", delegate.bytes("original")); }
        catch (IOException error) { throw new IllegalStateException("Missing synthetic sentinel"); }
        return result;
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS};
        MatrixCursor result = new MatrixCursor(columns); Object[] row = new Object[columns.length];
        for (int i=0;i<columns.length;i++) {
            switch(columns[i]) {
                case Root.COLUMN_ROOT_ID: case Root.COLUMN_DOCUMENT_ID: row[i]="root"; break;
                case Root.COLUMN_TITLE: row[i]="RdpSync Shared space fixture"; break;
                case Root.COLUMN_FLAGS: row[i]=Root.FLAG_SUPPORTS_CREATE; break;
                default: row[i]=null;
            }
        }
        result.addRow(row); return result;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        Cursor source = delegate.queryDocument(id, projection);
        if (!"source".equals(id) || (!unknownSize && !virtualSource)) return source;
        try {
            String[] columns=source.getColumnNames(); MatrixCursor result=new MatrixCursor(columns);
            while(source.moveToNext()) {
                Object[] row=new Object[columns.length];
                for(int i=0;i<columns.length;i++) {
                    if(unknownSize && Document.COLUMN_SIZE.equals(columns[i])) row[i]=null;
                    else if(virtualSource && Document.COLUMN_FLAGS.equals(columns[i])) row[i]=source.getInt(i)|Document.FLAG_VIRTUAL_DOCUMENT;
                    else if(source.isNull(i)) row[i]=null;
                    else if(source.getType(i)==Cursor.FIELD_TYPE_INTEGER) row[i]=source.getLong(i);
                    else row[i]=source.getString(i);
                }
                result.addRow(row);
            }
            return result;
        } finally { source.close(); }
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        return delegate.queryChildDocuments(parent, projection, sortOrder);
    }
    @Override public boolean isChildDocument(String parent,String id) { return delegate.isChildDocument(parent,id); }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException { return delegate.createDocument(parent,mime,name); }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException { return delegate.openDocument(id,mode,signal); }
    @Override public void deleteDocument(String id) throws FileNotFoundException { delegate.deleteDocument(id); }
}
