package com.rdp.sync.transfer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test APK only: generated fixture bytes, no user paths or network.
 *
 * DocumentsUI can launch this provider in the test APK's own process, without the target APK's
 * Kotlin runtime on its class path. Keep this class and its dependencies Java/framework-only;
 * the in-process ContentResolver.wrap tests alone cannot exercise that packaging boundary.
 */
public final class TransferFixtureDocumentsProvider extends DocumentsProvider {
    public File fixtureRoot;
    public boolean forceOutsideChild;
    public boolean forceExisting;
    public int writes;
    private final Map<String, Entry> files = new LinkedHashMap<>();

    private static final class Entry {
        final String name;
        final File file;
        Entry(String name, File file) { this.name = name; this.file = file; }
    }

    @Override public boolean onCreate() {
        if (getContext() == null) throw new IllegalStateException("Missing fixture context");
        fixtureRoot = new File(getContext().getCacheDir(), "transfer-provider-" + UUID.randomUUID());
        if (!fixtureRoot.mkdir()) throw new IllegalStateException("Cannot create fixture directory");
        return true;
    }

    public synchronized void seed(String id, String name, byte[] bytes) throws IOException {
        File file = new File(fixtureRoot, UUID.randomUUID().toString());
        Files.write(file.toPath(), bytes);
        Entry old = files.put(id, new Entry(name, file));
        if (old != null) old.file.delete();
    }

    public synchronized byte[] bytes(String id) throws IOException {
        return Files.readAllBytes(entry(id).file.toPath());
    }

    public synchronized List<String> ids() { return new ArrayList<>(files.keySet()); }

    public synchronized void cleanup() {
        // This provider creates only flat, opaque files in its own new directory.
        for (Entry value : files.values()) value.file.delete();
        files.clear();
        if (fixtureRoot != null) fixtureRoot.delete();
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Root.COLUMN_ROOT_ID:
                case Root.COLUMN_DOCUMENT_ID: row[i] = "root"; break;
                case Root.COLUMN_TITLE: row[i] = "RdpSync synthetic transfer fixture"; break;
                case Root.COLUMN_FLAGS: row[i] = Root.FLAG_SUPPORTS_CREATE; break;
                default: row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public synchronized Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        return cursor(projection, Collections.singletonList(id));
    }

    @Override public synchronized Cursor queryChildDocuments(String parent, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException();
        List<String> ids = new ArrayList<>(files.keySet());
        ids.remove("outside");
        return cursor(projection, ids);
    }

    private Cursor cursor(String[] projection, List<String> ids) throws FileNotFoundException {
        String[] columns = projection != null ? projection : new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String id : ids) {
            boolean root = "root".equals(id);
            Entry value = root ? null : entry(id);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                    case Document.COLUMN_DISPLAY_NAME:
                        row[i] = root ? "RdpSync synthetic transfer fixture" : value.name; break;
                    case Document.COLUMN_SIZE: row[i] = root ? 0L : value.file.length(); break;
                    case Document.COLUMN_MIME_TYPE:
                        row[i] = root ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                    case Document.COLUMN_FLAGS:
                        row[i] = root ? Document.FLAG_DIR_SUPPORTS_CREATE
                                : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE; break;
                    default: row[i] = null;
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override public synchronized String createDocument(String parent, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!"root".equals(parent) || Document.MIME_TYPE_DIR.equals(mimeType)) throw new FileNotFoundException();
        if (forceOutsideChild) return "outside";
        if (forceExisting) return "original";
        String id = UUID.randomUUID().toString();
        try { seed(id, displayName, new byte[0]); }
        catch (IOException ignored) { throw new FileNotFoundException("Cannot create fixture document"); }
        return id;
    }

    @Override public synchronized boolean isChildDocument(String parent, String id) {
        return "root".equals(parent) && !"outside".equals(id) && files.containsKey(id);
    }

    @Override public synchronized ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        Entry value = entry(id);
        if (mode.indexOf('w') >= 0) writes++;
        return ParcelFileDescriptor.open(value.file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public synchronized void deleteDocument(String id) throws FileNotFoundException {
        Entry value = entry(id);
        if (!value.file.delete()) throw new FileNotFoundException("Cannot delete fixture document");
        files.remove(id);
    }

    private Entry entry(String id) throws FileNotFoundException {
        Entry value = files.get(id);
        if (value == null) throw new FileNotFoundException();
        return value;
    }
}
