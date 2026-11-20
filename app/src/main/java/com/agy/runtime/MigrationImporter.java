package com.agy.runtime;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One-time import of a predecessor build's app data (conversations, settings,
 * sign-in state) from a backup zip in shared storage.
 *
 * The predecessor app (Leostryx / the old com.agy build) lives in a different
 * Android sandbox, so its data can only travel via a file both apps can see:
 * the user asks the old app's agent to zip the *contents* of its whole
 * $HOME/.gemini directory (app data, conversations, customizations, rules,
 * automations) to Documents/agy-backup.zip; on the next launch this importer
 * unpacks that zip into this app's .gemini dir before the core starts,
 * writes a marker, and never runs again. Any failure is logged and startup
 * continues fresh.
 */
public final class MigrationImporter {

    private static final String TAG = "MigrationImporter";
    private static final String ZIP_NAME = "agy-backup.zip";
    private static final String MARKER = ".migration-done";

    private MigrationImporter() {
    }

    public static void importIfPresent(Context ctx, File appDataDir) {
        try {
            // The backup holds the contents of the predecessor's .gemini dir,
            // so the import target is this app's .gemini dir (appDataDir's
            // parent), not the app data dir itself.
            File geminiDir = appDataDir.getParentFile();
            if (geminiDir == null) {
                return;
            }
            if (new File(geminiDir, MARKER).exists()) {
                return;
            }
            File zip = findBackup();
            if (zip == null) {
                return;
            }
            Log.i(TAG, "importing predecessor backup " + zip.getAbsolutePath()
                    + " (" + zip.length() + " bytes) into " + geminiDir);
            File parent = geminiDir.getParentFile();
            File tmp = new File(parent, geminiDir.getName() + ".importing");
            deleteRecursively(tmp);
            if (!tmp.mkdirs()) {
                Log.w(TAG, "cannot create staging dir " + tmp);
                return;
            }
            extract(zip, tmp);
            mergeInto(tmp, geminiDir);
            deleteRecursively(tmp);
            writeMarker(new File(geminiDir, MARKER));
            Log.i(TAG, "migration import complete");
        } catch (Exception e) {
            Log.w(TAG, "migration import failed; continuing with fresh data", e);
        }
    }

    private static File findBackup() {
        try {
            File docs = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS);
            File f = new File(docs, ZIP_NAME);
            if (f.isFile() && f.canRead()) {
                return f;
            }
        } catch (Exception ignored) {
        }
        File fallback = new File("/storage/emulated/0/Documents", ZIP_NAME);
        return (fallback.isFile() && fallback.canRead()) ? fallback : null;
    }

    private static void extract(File zip, File destDir) throws IOException {
        String destRoot = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(zip));
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.contains("..")) {
                    continue;
                }
                File out = new File(destDir, name);
                if (!out.getCanonicalPath().startsWith(destRoot)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (BufferedOutputStream bos =
                             new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** Moves staged files into place, overwriting same-path files. */
    private static void mergeInto(File from, File to) throws IOException {
        if (!to.exists()) {
            if (from.renameTo(to)) {
                return;
            }
        }
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        if (!to.exists() && !to.mkdirs()) {
            throw new IOException("cannot create " + to);
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                mergeInto(child, target);
            } else {
                if (target.exists() && !target.delete()) {
                    throw new IOException("cannot replace " + target);
                }
                if (!child.renameTo(target)) {
                    copyFile(child, target);
                    child.delete();
                }
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(dst))) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void writeMarker(File marker) {
        try (FileOutputStream fos = new FileOutputStream(marker)) {
            fos.write(("imported " + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
